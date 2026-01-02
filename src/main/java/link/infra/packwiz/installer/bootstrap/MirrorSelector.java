package link.infra.packwiz.installer.bootstrap;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Selects the fastest mirror from a list of mirrors by testing response time.
 */
public class MirrorSelector {

	/**
	 * List of GitHub mirror proxies for downloading releases
	 */
	private static final String[] GITHUB_MIRRORS = {
		"https://github.com",                    // 原始源
		"https://dgithub.xyz",                   // dgithub 镜像
		"https://hub.gitmirror.com/https://github.com",  // gitmirror
		"https://gh.idayer.com/https://github.com",      // idayer
		"https://ghproxy.cxkpro.top/https://github.com", // cxkpro
		"https://github.limoruirui.com/https://github.com", // limoruirui
		"https://gh.xxooo.cf/https://github.com"         // xxooo
	};

	private static final int TIMEOUT_MS = 5000; // 5秒超时
	private static final int TEST_TIMEOUT_MS = 5000; // 测速超时5秒
	private static final int TEST_DOWNLOAD_SIZE = 256 * 1024; // 测试下载256KB数据

	/**
	 * Generates all possible mirror URLs for a given GitHub release asset
	 * @param githubUrl Original GitHub URL
	 * @return List of mirror URLs
	 */
	public static List<String> generateMirrorUrls(String githubUrl) {
		List<String> urls = new ArrayList<>();

		if (!githubUrl.startsWith("https://github.com/")) {
			// 如果不是 GitHub URL，直接返回
			urls.add(githubUrl);
			return urls;
		}

		// 添加所有镜像
		for (String mirror : GITHUB_MIRRORS) {
			if (mirror.equals("https://github.com")) {
				urls.add(githubUrl);
			} else {
				// 替换 GitHub 域名为镜像
				String mirrorUrl = githubUrl.replace("https://github.com", mirror);
				urls.add(mirrorUrl);
			}
		}

		return urls;
	}

	/**
	 * Tests the download speed of a URL by downloading a small portion
	 * @param urlString URL to test
	 * @return Download speed in bytes per second, or 0 if failed
	 */
	private static long testUrl(String urlString) {
		HttpURLConnection conn = null;
		java.io.InputStream in = null;
		try {
			URL url = new URL(urlString);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(TEST_TIMEOUT_MS);
			conn.setReadTimeout(TEST_TIMEOUT_MS);
			conn.setInstanceFollowRedirects(true);
			// 请求前 256KB 数据
			conn.setRequestProperty("Range", "bytes=0-" + (TEST_DOWNLOAD_SIZE - 1));

			int responseCode = conn.getResponseCode();

			// 206 是 Partial Content，200 是完整内容
			if (responseCode != HttpURLConnection.HTTP_PARTIAL &&
			    responseCode != HttpURLConnection.HTTP_OK) {
				System.out.println("Mirror test: " + urlString + " - Failed (HTTP " + responseCode + ")");
				return 0;
			}

			in = conn.getInputStream();
			byte[] buffer = new byte[8192];
			long totalBytes = 0;
			long startTime = System.currentTimeMillis();
			int bytesRead;

			// 下载数据并计算速度
			while (totalBytes < TEST_DOWNLOAD_SIZE && (bytesRead = in.read(buffer)) != -1) {
				totalBytes += bytesRead;
			}

			long elapsedTime = System.currentTimeMillis() - startTime;

			if (elapsedTime == 0) {
				elapsedTime = 1; // 避免除以零
			}

			// 计算速度：字节/秒
			long speed = (totalBytes * 1000) / elapsedTime;

			// 转换为 KB/s 或 MB/s 显示
			String speedStr;
			if (speed > 1024 * 1024) {
				speedStr = String.format("%.2f MB/s", speed / (1024.0 * 1024.0));
			} else {
				speedStr = String.format("%.2f KB/s", speed / 1024.0);
			}

			System.out.println("Mirror test: " + urlString + " - " + speedStr);
			return speed;

		} catch (IOException e) {
			System.out.println("Mirror test: " + urlString + " - Failed (" + e.getMessage() + ")");
			return 0;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException ignored) {}
			}
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/**
	 * Selects the fastest mirror from a list of URLs
	 * @param urls List of mirror URLs to test
	 * @return The fastest URL, or the first URL if all tests fail
	 */
	public static String selectFastestMirror(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			throw new IllegalArgumentException("URL list cannot be empty");
		}

		if (urls.size() == 1) {
			return urls.get(0);
		}

		System.out.println("Testing " + urls.size() + " mirror(s) to find the fastest...");

		// 使用线程池并发测试所有镜像
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(urls.size(), 10));
		List<Future<MirrorResult>> futures = new ArrayList<>();

		for (String url : urls) {
			futures.add(executor.submit(() -> new MirrorResult(url, testUrl(url))));
		}

		// 收集所有结果
		List<MirrorResult> results = new ArrayList<>();
		for (Future<MirrorResult> future : futures) {
			try {
				results.add(future.get(TEST_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS));
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				// 忽略失败的测试
			}
		}

		executor.shutdown();

		// 找到速度最快的镜像
		if (results.isEmpty()) {
			System.out.println("All mirror tests failed, using first URL: " + urls.get(0));
			return urls.get(0);
		}

		Collections.sort(results);
		MirrorResult fastest = results.get(0);

		if (fastest.speed == 0) {
			System.out.println("All mirrors failed, using first URL: " + urls.get(0));
			return urls.get(0);
		}

		// 转换速度显示
		String speedStr;
		if (fastest.speed > 1024 * 1024) {
			speedStr = String.format("%.2f MB/s", fastest.speed / (1024.0 * 1024.0));
		} else {
			speedStr = String.format("%.2f KB/s", fastest.speed / 1024.0);
		}

		System.out.println("Selected fastest mirror: " + fastest.url + " (" + speedStr + ")");
		return fastest.url;
	}

	/**
	 * Helper class to store mirror test results
	 */
	private static class MirrorResult implements Comparable<MirrorResult> {
		final String url;
		final long speed; // bytes per second

		MirrorResult(String url, long speed) {
			this.url = url;
			this.speed = speed;
		}

		@Override
		public int compareTo(MirrorResult other) {
			// 速度越大越好，所以反向排序
			return Long.compare(other.speed, this.speed);
		}
	}
}
