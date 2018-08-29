package com.aiziyuer.app;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import lombok.Data;
import lombok.extern.log4j.Log4j;

@Log4j
public class TestSpiderServce {

	@Data
	public class Item {
		String url;

		public boolean isFile() {
			return !StringUtils.endsWith(url, "/");
		}
	}

	private CloseableHttpClient getHttpClient() throws NoSuchAlgorithmException, KeyManagementException {

//		HttpHost proxy = new HttpHost("127.0.0.1", 3128);

		RequestConfig requestConfig = RequestConfig.custom() //
				.setConnectionRequestTimeout(86400) //
				.setSocketTimeout(86400) //
				.setConnectTimeout(86400) //
				.setCookieSpec(CookieSpecs.DEFAULT) //
//				.setProxy(proxy) // 代理
				.build();

		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, new TrustManager[] { new X509TrustManager() {

			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}

			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			}

			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			}
		} }, null);

		Registry<ConnectionSocketFactory> reg = RegistryBuilder.<ConnectionSocketFactory>create() //
				.register("http", PlainConnectionSocketFactory.INSTANCE) //
				.register("https", new SSLConnectionSocketFactory(sc, NoopHostnameVerifier.INSTANCE)) //
				.build();

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(reg);
		connectionManager.setDefaultMaxPerRoute(100);

		SocketConfig socketConfig = SocketConfig.custom().setSoKeepAlive(true).setTcpNoDelay(true).build();

		CloseableHttpClient httpClient = HttpClients.custom() //
				.disableRedirectHandling() //
				.setConnectionManager(connectionManager)//
				.setDefaultSocketConfig(socketConfig) //
				.setDefaultRequestConfig(requestConfig) //
				.build();

		return httpClient;
	}

	@Test
	public void scan() throws KeyManagementException, NoSuchAlgorithmException, IOException {

		// 所有文件的集合
		Set<String> remoteFileSet = Collections.synchronizedSet(new HashSet<String>());

		// 全局队列
		BlockingDeque<Item> g_queue = new LinkedBlockingDeque<>();

		// 结束标记位
		Item FINISH_FLAG = new Item();

		// 线程数
		int threadCount = 500;

		// 标记线程是否正在工作
		boolean[] startWorkingFlag = new boolean[threadCount];
		for (int i = 0; i < startWorkingFlag.length; i++) {
			startWorkingFlag[i] = false;
		}

		// 线程池
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {

			int threadIndex = i;

			executor.execute(() -> {

				Thread.currentThread().setName("WorkingThread-" + threadIndex);

				try (CloseableHttpClient httpClient = getHttpClient()) {

					while (true) {

						Item currentItem;

						// 保证每一时刻只有一线程可以从队列中获取元素
						// synchronized (g_queue) {
						currentItem = g_queue.take();
						if (currentItem == FINISH_FLAG) {
							g_queue.add(FINISH_FLAG);
							break;
						}
						// }

						// 工作线程进入工作
						startWorkingFlag[threadIndex] = true;

						// 如果是文件就直接记录
						if (currentItem.isFile()) {
							synchronized (remoteFileSet) {
//								remoteFileSet.add(currentItem.url);
							}
							// 任务结束
							startWorkingFlag[threadIndex] = false;
							continue;
						}

						log.info("list currentItem.ur: " + currentItem.url);

						CloseableHttpResponse response = httpClient.execute(new HttpGet(currentItem.url));
						if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null)
							return;

						String body = EntityUtils.toString(response.getEntity(), "utf-8");

						Document doc = Jsoup.parse(body);
						List<Item> items = doc.select("a[href]").stream() //
								.map(e -> e.attr("href")) //
								.filter(a -> !StringUtils.equals(a, "../")) //
								.map(a -> {

									Item item = new Item();
									item.url = String.format("%s/%s", currentItem.url, a)
											.replaceAll("(?<!(http:|https:))[/]+", "/");

									return item;
								}) //
								.collect(Collectors.toList());

						synchronized (g_queue) {
							g_queue.addAll(items);
						}

						// 工作线程结束
						startWorkingFlag[threadIndex] = false;

					}

				} catch (KeyManagementException | NoSuchAlgorithmException e) {
					log.error("ssl error, ", e);
				} catch (IOException e) {
					log.error("parse dom error, ", e);
				} catch (InterruptedException e) {
					log.error("thread interrupted, ", e);
				}

			});
		}

		Item home = new Item();
		home.url = "http://repo1.maven.org/maven2/";
//		home.url = "http://repo1.maven.org/maven2/ee/bitweb/bitweb-ogone/";
		g_queue.add(home);

		while (true) {

			// 队列为空就说明任务做完了(防止刚开始就结束, 增加检测文件列表功能)
			if (g_queue.isEmpty() && !remoteFileSet.isEmpty()) {

				boolean isAllThreadFinished = true;

				for (int i = 0; i < startWorkingFlag.length; i++)
					if (startWorkingFlag[i]) {
						isAllThreadFinished = false;
						break;
					}

				// 如果所有线程都已经完成
				if (isAllThreadFinished) {
					g_queue.add(FINISH_FLAG);
					break;
				}
			}
		}

		// 关闭所有线程
		executor.shutdown();

		log.info("//////////////////////////////remoteFileSet start ///////////////////////////////////");
		log.info(remoteFileSet);
		log.info("//////////////////////////////remoteFileSet end ///////////////////////////////////");

	}

}
