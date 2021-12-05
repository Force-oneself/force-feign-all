package org.springframework.cloud.openfeign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.*;

/**
 * @author Force-oneself
 * @Description ParamEncryptRequestInterceptor
 * @date 2021-08-23
 */
public class ParamEncryptRequestInterceptor implements RequestInterceptor {

	private List<String> urlList;

	@Override
	public void apply(RequestTemplate template) {
		if (shouldSkip(template)) {
			return;
		}
		Collection<String> paramList = template.queries().get("param");
		String param = paramList.iterator().next();
		try {
			// 加密

		} catch (Exception e) {
			e.printStackTrace();
		}
		Map<String, Collection<String>> newQueries = new HashMap<>();
		Collection<String> value = new ArrayList<>();
		value.add(param);
		newQueries.put("param", value);
		template.queries(newQueries);// 替换原有对象
	}

	private boolean shouldSkip(RequestTemplate template) {
		// 根据URL地址过滤请求
		return !urlList.contains(template.url());
	}
}
