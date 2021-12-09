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

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Scans for interfaces that declare they are feign clients (via
 * {@link org.springframework.cloud.openfeign.FeignClient} <code>@FeignClient</code>).
 * Configures component scanning directives for use with
 * {@link org.springframework.context.annotation.Configuration}
 * <code>@Configuration</code> classes.
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @since 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(FeignClientsRegistrar.class)
public @interface EnableFeignClients {

    /**
     * {@link basePackages()} 属性的别名。允许更简洁的注释声明，
     * 例如：{@code @ComponentScan("org.my.pkg")}
     * 而不是 {@code @ComponentScan(basePackages="org.my.pkg")}。
     *
     * @return the array of 'basePackages'.
     */
    String[] value() default {};

    /**
     * 用于扫描带注释组件的基本包。
     * <p> {@link value()} 是此属性的别名（并与之互斥）。
     * <p> 使用 {@link basePackageClasses()} 作为基于字符串的包名称的类型安全替代方案。
     *
     * @return the array of 'basePackages'.
     */
    String[] basePackages() default {};

    /**
     * {@link basePackages()} 的类型安全替代方案，用于指定要扫描带注释组件的包。将扫描指定的每个类的包。
     * <p> 考虑在每个包中创建一个特殊的无操作标记类或接口，除了被此属性引用外，没有其他用途。
     *
     * @return the array of 'basePackageClasses'.
     */
    Class<?>[] basePackageClasses() default {};

    /**
     * 所有 feign 客户端的自定义 <code>@Configuration<code>。
     * 可以包含组成客户端的部分的覆盖 <code>@Bean<code> 定义，
     * 例如 {@link feign.codec.Decoder}、{@link feign.codec.Encoder}、{@link feign.Contract} .
     *
     * @return list of default configurations
     * @see FeignClientsConfiguration for the defaults
     */
    Class<?>[] defaultConfiguration() default {};

    /**
     * 用@FeignClient 注释的类列表。如果不为空，则禁用类路径扫描.
     *
     * @return list of FeignClient classes
     */
    Class<?>[] clients() default {};

}
