/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import feign.*;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.ribbon.LoadBalancerFeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 * @author Marcin Grzejszczak
 */
public class FeignClientFactoryBean implements FactoryBean<Object>, InitializingBean,
        ApplicationContextAware, BeanFactoryAware {

    /***********************************
     * 警告！此类中的任何内容都不应该是@Autowired。由于某些生命周期竞争条件，它会导致 NPE.
     ***********************************/

    private Class<?> type;

    private String name;

    private String url;

    private String contextId;

    private String path;

    private boolean decode404;

    private boolean inheritParentContext = true;

    private ApplicationContext applicationContext;

    private BeanFactory beanFactory;

    private Class<?> fallback = void.class;

    private Class<?> fallbackFactory = void.class;

    private int readTimeoutMillis = new Request.Options().readTimeoutMillis();

    private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();

    @Override
    public void afterPropertiesSet() {
        Assert.hasText(contextId, "Context id must be set");
        Assert.hasText(name, "Name must be set");
    }

    protected Feign.Builder feign(FeignContext context) {
        // 根据FeignContext获取到对应的配置实例信息
        FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
        Logger logger = loggerFactory.create(type);

        // @formatter:off
        Feign.Builder builder = get(context, Feign.Builder.class)
                // required values
                .logger(logger)
                // 编码
                .encoder(get(context, Encoder.class))
                // 解码
                .decoder(get(context, Decoder.class))
                .contract(get(context, Contract.class));
        // @formatter:on

        // 配置Feign的属性信息通过配置文件
        configureFeign(context, builder);

        applyBuildCustomizers(context, builder);

        return builder;
    }

    private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
        Map<String, FeignBuilderCustomizer> customizerMap = context
                .getInstances(contextId, FeignBuilderCustomizer.class);

        if (customizerMap != null) {
            customizerMap.values().stream()
                    .sorted(AnnotationAwareOrderComparator.INSTANCE)
                    // Force-Spring 拓展点：FeignBuilderCustomizer自定义配置Feign的Builder配置
                    .forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
        }
    }

    protected void configureFeign(FeignContext context, Feign.Builder builder) {
        // 获取到properties
        FeignClientProperties properties = beanFactory != null
                ? beanFactory.getBean(FeignClientProperties.class)
                : applicationContext.getBean(FeignClientProperties.class);

        FeignClientConfigurer feignClientConfigurer = getOptional(context,
                FeignClientConfigurer.class);
        setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

        // 配置各种属性，允许覆盖默认配置
        if (properties != null && inheritParentContext) {
            // 是否使用配置文件为默认配置
            if (properties.isDefaultToProperties()) {
                // 配置上述的配置的相关类
                configureUsingConfiguration(context, builder);
                // 配置属性文件中的默认配置
                configureUsingProperties(
                        properties.getConfig().get(properties.getDefaultConfig()),
                        builder);
                // 配置属性文件中@FeignClient的配置，将会覆盖上述配置
                configureUsingProperties(properties.getConfig().get(contextId), builder);
            } else {
                // 与上述一样只是与configureUsingConfiguration交换位置
                configureUsingProperties(
                        properties.getConfig().get(properties.getDefaultConfig()),
                        builder);
                // 覆盖默认配置
                configureUsingProperties(properties.getConfig().get(contextId), builder);
                configureUsingConfiguration(context, builder);
            }
        } else {
            // 仅仅配置内部的相关配置
            configureUsingConfiguration(context, builder);
        }
    }

    protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
        // 日志级别
        Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
        if (level != null) {
            builder.logLevel(level);
        }
        // 重试器
        Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
        if (retryer != null) {
            builder.retryer(retryer);
        }
        // 异常解码器
        ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
        if (errorDecoder != null) {
            builder.errorDecoder(errorDecoder);
        } else {
            // 异常解码器工厂
            FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
            if (errorDecoderFactory != null) {
                ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
                builder.errorDecoder(factoryErrorDecoder);
            }
        }
        // 请求配置
        Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
        if (options != null) {
            builder.options(options);
            // 读取超时
            readTimeoutMillis = options.readTimeoutMillis();
            // 连接超时
            connectTimeoutMillis = options.connectTimeoutMillis();
        }
        // 请求拦截器
        Map<String, RequestInterceptor> requestInterceptors
                = getInheritedAwareInstances(context, RequestInterceptor.class);
        if (requestInterceptors != null) {
            List<RequestInterceptor> interceptors = new ArrayList<>(
                    requestInterceptors.values());
            AnnotationAwareOrderComparator.sort(interceptors);
            builder.requestInterceptors(interceptors);
        }
        // 对象转Map编码器
        QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
        if (queryMapEncoder != null) {
            builder.queryMapEncoder(queryMapEncoder);
        }
        if (decode404) {
            // 404解码
            builder.decode404();
        }
        // 异常传播策略
        ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(
                context, ExceptionPropagationPolicy.class);
        if (exceptionPropagationPolicy != null) {
            builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
        }
    }

    protected void configureUsingProperties(
            FeignClientProperties.FeignClientConfiguration config,
            Feign.Builder builder) {
        if (config == null) {
            return;
        }

        // 日志级别
        if (config.getLoggerLevel() != null) {
            builder.logLevel(config.getLoggerLevel());
        }
        // 连接超时
        connectTimeoutMillis = config.getConnectTimeout() != null
                ? config.getConnectTimeout()
                : connectTimeoutMillis;
        // 读取超时
        readTimeoutMillis = config.getReadTimeout() != null
                ? config.getReadTimeout()
                : readTimeoutMillis;

        // Client配置信息
        builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS,
                readTimeoutMillis, TimeUnit.MILLISECONDS, true));
        // 重试器
        if (config.getRetryer() != null) {
            Retryer retryer = getOrInstantiate(config.getRetryer());
            builder.retryer(retryer);
        }
        // 异常解码器
        if (config.getErrorDecoder() != null) {
            ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
            builder.errorDecoder(errorDecoder);
        }

        // 添加请求拦截器
        if (config.getRequestInterceptors() != null
                && !config.getRequestInterceptors().isEmpty()) {
            // 这会将请求拦截器添加到构建器，而不是替换现有的
            for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
                RequestInterceptor interceptor = getOrInstantiate(bean);
                builder.requestInterceptor(interceptor);
            }
        }

        // 404解码
        if (config.getDecode404() != null) {
            if (config.getDecode404()) {
                builder.decode404();
            }
        }

        if (Objects.nonNull(config.getEncoder())) {
            // 编码器
            builder.encoder(getOrInstantiate(config.getEncoder()));
        }

        // 默认的headers
        if (Objects.nonNull(config.getDefaultRequestHeaders())) {
            builder.requestInterceptor(requestTemplate ->
                    requestTemplate.headers(config.getDefaultRequestHeaders()));
        }

        // 默认的参数
        if (Objects.nonNull(config.getDefaultQueryParameters())) {
            builder.requestInterceptor(requestTemplate ->
                    requestTemplate.queries(config.getDefaultQueryParameters()));
        }

        // 解码器
        if (Objects.nonNull(config.getDecoder())) {
            builder.decoder(getOrInstantiate(config.getDecoder()));
        }

        // 契约
        if (Objects.nonNull(config.getContract())) {
            builder.contract(getOrInstantiate(config.getContract()));
        }

        // 异常传播策略
        if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
            builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
        }
    }

    private <T> T getOrInstantiate(Class<T> tClass) {
        try {
            return beanFactory != null
                    ? beanFactory.getBean(tClass)
                    : applicationContext.getBean(tClass);
        } catch (NoSuchBeanDefinitionException e) {
            return BeanUtils.instantiateClass(tClass);
        }
    }

    protected <T> T get(FeignContext context, Class<T> type) {
        T instance = context.getInstance(contextId, type);
        if (instance == null) {
            throw new IllegalStateException(
                    "No bean found of type " + type + " for " + contextId);
        }
        return instance;
    }

    protected <T> T getOptional(FeignContext context, Class<T> type) {
        return context.getInstance(contextId, type);
    }

    protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
        if (inheritParentContext) {
            return getOptional(context, type);
        } else {
            return context.getInstanceWithoutAncestors(contextId, type);
        }
    }

    protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context, Class<T> type) {
        if (inheritParentContext) {
            return context.getInstances(contextId, type);
        } else {
            return context.getInstancesWithoutAncestors(contextId, type);
        }
    }

    protected <T> T loadBalance(Feign.Builder builder, FeignContext context,
                                HardCodedTarget<T> target) {
        Client client = getOptional(context, Client.class);
        if (client != null) {
            builder.client(client);
            Targeter targeter = get(context, Targeter.class);
            return targeter.target(this, builder, context, target);
        }

        throw new IllegalStateException(
                "No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon?");
    }

    @Override
    public Object getObject() {
        return getTarget();
    }

    /**
     * @param <T> Feign客户端的目标类型
     * @return a {@link Feign} 使用指定数据和上下文创建的客户端
     * information
     */
    <T> T getTarget() {
        FeignContext context = beanFactory != null
                ? beanFactory.getBean(FeignContext.class)
                : applicationContext.getBean(FeignContext.class);
        // 初始化Feign的一些配置
        Feign.Builder builder = feign(context);

        // Force-Spring 知识点：配置了url无法使用负载均衡
        if (!StringUtils.hasText(url)) {
            if (!name.startsWith("http")) {
                url = "http://" + name;
            } else {
                url = name;
            }
            // 拼接路径
            url += cleanPath();
            // 负载均衡
            return (T) loadBalance(builder, context,
                    new HardCodedTarget<>(type, name, url));
        }
        if (StringUtils.hasText(url) && !url.startsWith("http")) {
            url = "http://" + url;
        }
        // 完整的请求路径
        String url = this.url + cleanPath();
        // 获取到配置的客户端
        Client client = getOptional(context, Client.class);
        if (client != null) {
            // 这两种客户端都是代理类实现所有需要获取到实际的客户端
            if (client instanceof LoadBalancerFeignClient) {
                // 没有负载平衡，因为我们有一个 url，但功能区在类路径上，所以解开
                client = ((LoadBalancerFeignClient) client).getDelegate();
            }
            if (client instanceof FeignBlockingLoadBalancerClient) {
                // 不是负载均衡，因为我们有一个 url，但 Spring Cloud LoadBalancer 在类路径上，所以解包
                client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
            }
            builder.client(client);
        }
        // 获取代理器
        Targeter targeter = get(context, Targeter.class);
        // 开始生成代理类
        return (T) targeter.target(this, builder, context,
                new HardCodedTarget<>(type, name, url));
    }

    private String cleanPath() {
        // 去除首尾空白
        String path = this.path.trim();
        if (StringUtils.hasLength(path)) {
            // 给首位添加【/】
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            // 去除末尾【/】
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        return path;
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public Class<?> getType() {
        return type;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isDecode404() {
        return decode404;
    }

    public void setDecode404(boolean decode404) {
        this.decode404 = decode404;
    }

    public boolean isInheritParentContext() {
        return inheritParentContext;
    }

    public void setInheritParentContext(boolean inheritParentContext) {
        this.inheritParentContext = inheritParentContext;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context;
        beanFactory = context;
    }

    public Class<?> getFallback() {
        return fallback;
    }

    public void setFallback(Class<?> fallback) {
        this.fallback = fallback;
    }

    public Class<?> getFallbackFactory() {
        return fallbackFactory;
    }

    public void setFallbackFactory(Class<?> fallbackFactory) {
        this.fallbackFactory = fallbackFactory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FeignClientFactoryBean that = (FeignClientFactoryBean) o;
        return Objects.equals(applicationContext, that.applicationContext)
                && Objects.equals(beanFactory, that.beanFactory)
                && decode404 == that.decode404
                && inheritParentContext == that.inheritParentContext
                && Objects.equals(fallback, that.fallback)
                && Objects.equals(fallbackFactory, that.fallbackFactory)
                && Objects.equals(name, that.name) && Objects.equals(path, that.path)
                && Objects.equals(type, that.type) && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(applicationContext, beanFactory, decode404,
                inheritParentContext, fallback, fallbackFactory, name, path, type, url);
    }

    @Override
    public String toString() {
        return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type)
                .append(", ").append("name='").append(name).append("', ").append("url='")
                .append(url).append("', ").append("path='").append(path).append("', ")
                .append("decode404=").append(decode404).append(", ")
                .append("inheritParentContext=").append(inheritParentContext).append(", ")
                .append("applicationContext=").append(applicationContext).append(", ")
                .append("beanFactory=").append(beanFactory).append(", ")
                .append("fallback=").append(fallback).append(", ")
                .append("fallbackFactory=").append(fallbackFactory).append("}")
                .toString();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

}
