package org.springframework.cloud.openfeign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * @author Force-oneself
 * @Description ParamLogRequestInterceptor
 * @date 2021-08-23
 */
public class ParamLogRequestInterceptor implements RequestInterceptor {

	private final static Logger log = LoggerFactory.getLogger(ParamLogRequestInterceptor.class);

	@Override
	public void apply(RequestTemplate template) {
		ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

		if (attributes != null) {
			HttpServletRequest request = attributes.getRequest();
			template.header("sessionId", request.getHeader("sessionId"));
		}
		switch (template.method()) {
			case "GET":
				log.info("{}OpenFeign GET请求，请求路径：【{}", System.lineSeparator(), template.url());
				break;
			case "POST":
				log.info("{}OpenFeign POST请求，请求路径：【{}】，请求参数：【{}】",
					System.lineSeparator(),
					template.url(),
					new String(template.body(), template.requestCharset()));
				break;
			default:
		}
	}
}
