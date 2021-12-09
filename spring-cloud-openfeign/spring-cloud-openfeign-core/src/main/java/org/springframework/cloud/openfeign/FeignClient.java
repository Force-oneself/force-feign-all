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

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Annotation for interfaces declaring that a REST client with that interface should be
 * created (e.g. for autowiring into another component). If ribbon is available it will be
 * used to load balance the backend requests, and the load balancer can be configured
 * using a <code>@RibbonClient</code> with the same name (i.e. value) as the feign client.
 *
 * @author Spencer Gibb
 * @author Venil Noronha
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface FeignClient {

    /**
     * 带有可选协议前缀的服务名称。 {@link name() name} 的同义词。
     * 无论是否提供 url，都必须为所有客户端指定名称。可以指定为属性键，
     * 例如：{propertyKey}.
     *
     * @return the name of the service with optional protocol prefix
     */
    @AliasFor("name")
    String value() default "";

    /**
     * 带有可选协议前缀的服务 ID。 {@link value() value} 的同义词
     *
     * @return the service id with optional protocol prefix
     * @deprecated use {@link #name() name} instead
     */
    @Deprecated
    String serviceId() default "";

    /**
     * 如果存在，这将用作 bean 名称而不是名称，但不会用作服务 ID.
     *
     * @return bean name instead of name if present
     */
    String contextId() default "";

    /**
     * @return The service id with optional protocol prefix. Synonym for {@link #value()
     * value}.
     */
    @AliasFor("value")
    String name() default "";

    /**
     * @return the <code>@Qualifier</code> value for the feign client.
     */
    String qualifier() default "";

    /**
     * @return 绝对 URL 或可解析的主机名 (the protocol is optional).
     */
    String url() default "";

    /**
     * @return 是否应该解码 404s 而不是抛出 FeignExceptions
     */
    boolean decode404() default false;

    /**
     * feign 客户端的自定义配置类。
     * 可以包含组成客户端的部分的覆盖 <code>@Bean<code> 定义，
     * 例如
     * {@link feign.codec.Decoder}
     * {@link feign.codec.Encoder}
     * {@link feign.Contract}
     *
     * @return list of configurations for feign client
     * @see FeignClientsConfiguration for the defaults
     */
    Class<?>[] configuration() default {};

    /**
     * 指定 Feign 客户端接口的回退类。回退类必须实现这个注解所注解的接口，并且是一个有效的 spring bean。
     *
     * @return fallback class for the specified Feign client interface
     */
    Class<?> fallback() default void.class;

    /**
     * 为指定的 feign 客户端接口定义一个回退工厂。回退工厂必须生成回退类的实例，
     * 这些实例实现由 {@link feignclient} 注释的接口。回退工厂必须是有效的 spring bean。
     *
     * @return fallback factory for the specified Feign client interface
     * @see feign.hystrix.FallbackFactory for details.
     * @see FallbackFactory for details.
     */
    Class<?> fallbackFactory() default void.class;

    /**
     * @return 所有方法级映射使用的路径前缀。可以使用或不使用 <code>@RibbonClient<code>。
     */
    String path() default "";

    /**
     * 设置为false存在fallback将会导致多个Bean无法注入
     *
     * @return 是否将 feign 代理标记为主要 bean。默认为真。
     */
    boolean primary() default true;

}
