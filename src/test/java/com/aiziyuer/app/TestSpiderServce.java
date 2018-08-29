package com.aiziyuer.app;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
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

		HttpHost proxy = new HttpHost("127.0.0.1", 3128);

		RequestConfig requestConfig = RequestConfig.custom() //
				.setConnectionRequestTimeout(86400) //
				.setSocketTimeout(86400) //
				.setConnectTimeout(86400) //
				.setCookieSpec(CookieSpecs.DEFAULT) //
				.setProxy(proxy) // 代理
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

		try (CloseableHttpClient httpClient = getHttpClient()) {

			String baseUrl = "http://repo1.maven.org/maven2/";
			CloseableHttpResponse response = httpClient.execute(new HttpGet(baseUrl));

			if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null)
				return;

			String body = EntityUtils.toString(response.getEntity(), "utf-8");

			Document doc = Jsoup.parse(body);
			List<Item> items = doc.select("a[href]").stream() //
					.map(e -> e.attr("href")) //
					.filter(a -> !StringUtils.equals(a, "../")) //
					.map(a -> {

						Item item = new Item();
						item.url = String.format("%s/%s", baseUrl, a).replaceAll("(?<!(http:|https:))[/]+", "/");

						return item;
					}) //
					.collect(Collectors.toList());

			log.info("items: " + items);
		}

	}

}
