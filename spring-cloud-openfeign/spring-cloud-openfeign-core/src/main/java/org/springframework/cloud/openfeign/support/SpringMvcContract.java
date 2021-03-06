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

package org.springframework.cloud.openfeign.support;

import feign.*;
import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.cloud.openfeign.annotation.*;
import org.springframework.cloud.openfeign.encoding.HttpEncoding;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import static org.springframework.cloud.openfeign.support.FeignUtils.addTemplateParameter;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * @author Spencer Gibb
 * @author Abhijit Sarkar
 * @author Halvdan Hoem Grelland
 * @author Aram Peres
 * @author Olga Maciaszek-Sharma
 * @author Aaron Whiteside
 * @author Artyom Romanenko
 * @author Darren Foong
 * @author Ram Anaswara
 */
public class SpringMvcContract extends Contract.BaseContract
        implements ResourceLoaderAware {

    private static final String ACCEPT = "Accept";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor
            .valueOf(String.class);

    private static final TypeDescriptor ITERABLE_TYPE_DESCRIPTOR = TypeDescriptor
            .valueOf(Iterable.class);

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final Map<Class<? extends Annotation>, AnnotatedParameterProcessor> annotatedArgumentProcessors;

    private final Map<String, Method> processedMethods = new HashMap<>();

    private final ConversionService conversionService;

    private final ConvertingExpanderFactory convertingExpanderFactory;

    private ResourceLoader resourceLoader = new DefaultResourceLoader();

    private boolean decodeSlash;

    public SpringMvcContract() {
        this(Collections.emptyList());
    }

    public SpringMvcContract(
            List<AnnotatedParameterProcessor> annotatedParameterProcessors) {
        this(annotatedParameterProcessors, new DefaultConversionService());
    }

    public SpringMvcContract(
            List<AnnotatedParameterProcessor> annotatedParameterProcessors,
            ConversionService conversionService) {
        this(annotatedParameterProcessors, conversionService, true);
    }

    public SpringMvcContract(
            List<AnnotatedParameterProcessor> annotatedParameterProcessors,
            ConversionService conversionService, boolean decodeSlash) {
        Assert.notNull(annotatedParameterProcessors,
                "Parameter processors can not be null.");
        Assert.notNull(conversionService, "ConversionService can not be null.");

        // ???????????????????????????????????????
        List<AnnotatedParameterProcessor> processors = getDefaultAnnotatedArgumentsProcessors();
        // Force-Spring ?????????????????????????????????????????????AnnotatedParameterProcessor
        processors.addAll(annotatedParameterProcessors);

        annotatedArgumentProcessors = toAnnotatedArgumentProcessorMap(processors);
        this.conversionService = conversionService;
        convertingExpanderFactory = new ConvertingExpanderFactory(conversionService);
        this.decodeSlash = decodeSlash;
    }

    private static TypeDescriptor createTypeDescriptor(Method method, int paramIndex) {
        Parameter parameter = method.getParameters()[paramIndex];
        MethodParameter methodParameter = MethodParameter.forParameter(parameter);
        TypeDescriptor typeDescriptor = new TypeDescriptor(methodParameter);

        // Feign ??? Param.Expander ????????? Iterable ??????????????????
        // ?????????????????????????????????????????????????????? TypeDescriptor
        if (typeDescriptor.isAssignableTo(ITERABLE_TYPE_DESCRIPTOR)) {
            TypeDescriptor elementTypeDescriptor = getElementTypeDescriptor(typeDescriptor);

            checkState(elementTypeDescriptor != null,
                    "Could not resolve element type of Iterable type %s. Not declared?",
                    typeDescriptor);

            typeDescriptor = elementTypeDescriptor;
        }
        return typeDescriptor;
    }

    private static TypeDescriptor getElementTypeDescriptor(
            TypeDescriptor typeDescriptor) {
        TypeDescriptor elementTypeDescriptor = typeDescriptor.getElementTypeDescriptor();
        // ????????????????????????????????????????????????????????????gh-135
        if (elementTypeDescriptor == null
                && Iterable.class.isAssignableFrom(typeDescriptor.getType())) {
            ResolvableType type = typeDescriptor.getResolvableType().as(Iterable.class).getGeneric(0);
            if (type.resolve() == null) {
                return null;
            }
            return new TypeDescriptor(type, null, typeDescriptor.getAnnotations());
        }
        return elementTypeDescriptor;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    protected void processAnnotationOnClass(MethodMetadata data, Class<?> clz) {
        // Force-Spring ????????????FeignClient????????????????????????????????????????????????????????????@RequestMapping
        if (clz.getInterfaces().length == 0) {
            RequestMapping classAnnotation = findMergedAnnotation(clz, RequestMapping.class);
            // ????????????????????????????????????????????????
            if (classAnnotation != null && classAnnotation.value().length > 0) {
                // Force-Spring ????????????FeignClient?????????@RequestMapping???value?????????????????????
                String pathValue = emptyToNull(classAnnotation.value()[0]);
                // ????????????
                pathValue = resolve(pathValue);
                // ????????????
                if (!pathValue.startsWith("/")) {
                    pathValue = "/" + pathValue;
                }
                // ???uri?????????MethodMetadata
                data.template().uri(pathValue);
                // ????????????????????????/???????????????
                if (data.template().decodeSlash() != decodeSlash) {
                    data.template().decodeSlash(decodeSlash);
                }
            }
        }
    }

    @Override
    public MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
        // ?????????????????????
        processedMethods.put(Feign.configKey(targetType, method), method);
        // ?????????????????????????????????
        MethodMetadata md = super.parseAndValidateMetadata(targetType, method);

        RequestMapping classAnnotation = findMergedAnnotation(targetType, RequestMapping.class);
        if (classAnnotation != null) {
            // produces -- ????????????????????? this ?????????????????????
            if (!md.template().headers().containsKey(ACCEPT)) {
                parseProduces(md, method, classAnnotation);
            }

            // consumes -- ????????????????????? this ???????????????????????????
            if (!md.template().headers().containsKey(CONTENT_TYPE)) {
                parseConsumes(md, method, classAnnotation);
            }

            // headers -- ???????????????????????????????????????????????????????????????
            parseHeaders(md, method, classAnnotation);
        }
        return md;
    }

    @Override
    protected void processAnnotationOnMethod(MethodMetadata data,
                                             Annotation methodAnnotation, Method method) {
        // Force-Spring ????????????FeignClient????????????@CollectionFormat?????????
        if (methodAnnotation instanceof CollectionFormat) {
            CollectionFormat collectionFormat = findMergedAnnotation(method, CollectionFormat.class);
            // ???????????????????????????
            data.template().collectionFormat(collectionFormat.value());
        }
        // ??????@RequestMapping
        if (!(methodAnnotation instanceof RequestMapping)
                // ?????????????????????@RequestMapping
                && !methodAnnotation.annotationType().isAnnotationPresent(RequestMapping.class)) {
            return;
        }

        // Force-Spring ????????????FeignClient????????????@RequestMapping?????????
        RequestMapping methodMapping = findMergedAnnotation(method, RequestMapping.class);
        // ????????????
        RequestMethod[] methods = methodMapping.method();
        if (methods.length == 0) {
            // ??????GET??????
            methods = new RequestMethod[]{RequestMethod.GET};
        }
        // ????????????Method???????????????????????????
        checkOne(method, methods, "method");
        // ??????????????????????????????
        data.template().method(Request.HttpMethod.valueOf(methods[0].name()));

        // Force-Spring ????????????FeignClient??????????????????@RequestMapping??????????????????????????????
        checkAtMostOne(method, methodMapping.value(), "value");
        // ???????????????
        if (methodMapping.value().length > 0) {
            // ?????????????????????????????? ???????????????
            String pathValue = emptyToNull(methodMapping.value()[0]);

            if (pathValue != null) {
                // ????????????
                pathValue = resolve(pathValue);
                // ????????????????????????????????? @RequestMapping ????????????
                if (!pathValue.startsWith("/")
                        // ??????@RequestMapping????????????'/'?????????????????????
                        && !data.template().path().endsWith("/")) {
                    pathValue = "/" + pathValue;
                }
                // ????????????????????????
                data.template().uri(pathValue, true);
                // ??????'/'??????????????????
                if (data.template().decodeSlash() != decodeSlash) {
                    data.template().decodeSlash(decodeSlash);
                }
            }
        }

        // produces
        parseProduces(data, method, methodMapping);

        // consumes
        parseConsumes(data, method, methodMapping);

        // headers
        parseHeaders(data, method, methodMapping);

        data.indexToExpander(new LinkedHashMap<>());
    }

    private String resolve(String value) {
        if (StringUtils.hasText(value)
                && resourceLoader instanceof ConfigurableApplicationContext) {
            // ?????????${}???????????????
            return ((ConfigurableApplicationContext) resourceLoader).getEnvironment()
                    .resolvePlaceholders(value);
        }
        return value;
    }

    private void checkAtMostOne(Method method, Object[] values, String fieldName) {
        checkState(values != null && (values.length == 0 || values.length == 1),
                "Method %s can only contain at most 1 %s field. Found: %s",
                method.getName(), fieldName,
                values == null ? null : Arrays.asList(values));
    }

    private void checkOne(Method method, Object[] values, String fieldName) {
        checkState(values != null && values.length == 1,
                "Method %s can only contain 1 %s field. Found: %s", method.getName(),
                fieldName, values == null ? null : Arrays.asList(values));
    }

    @Override
    protected boolean processAnnotationsOnParameter(MethodMetadata data,
                                                    Annotation[] annotations, int paramIndex) {
        boolean isHttpAnnotation = false;

        // ???????????????
        AnnotatedParameterProcessor.AnnotatedParameterContext context = new SimpleAnnotatedParameterContext(
                data, paramIndex);
        // ???????????????????????????
        Method method = processedMethods.get(data.configKey());
        for (Annotation parameterAnnotation : annotations) {
            // ?????????????????????
            AnnotatedParameterProcessor processor = annotatedArgumentProcessors
                    .get(parameterAnnotation.annotationType());
            if (processor != null) {
                Annotation processParameterAnnotation;
                // ???????????????@AliasFor?????????????????? String value() ??????value??????????????????????????????
                processParameterAnnotation = synthesizeWithMethodParameterNameAsFallbackValue(
                        parameterAnnotation, method, paramIndex);
                // ????????????
                // Force-Spring ????????????AnnotatedParameterProcessor??????processArgument
                isHttpAnnotation |= processor.processArgument(context, processParameterAnnotation, method);
            }
        }

        // ???multipart/form-data
        if (!isMultipartFormData(data)
                // ???Http??????
                && isHttpAnnotation
                // ??????Param.Expander????????????
                && data.indexToExpander().get(paramIndex) == null) {
            TypeDescriptor typeDescriptor = createTypeDescriptor(method, paramIndex);
            if (conversionService.canConvert(typeDescriptor, STRING_TYPE_DESCRIPTOR)) {
                Param.Expander expander = convertingExpanderFactory
                        .getExpander(typeDescriptor);
                if (expander != null) {
                    // ??????????????????
                    data.indexToExpander().put(paramIndex, expander);
                }
            }
        }
        return isHttpAnnotation;
    }

    private void parseProduces(MethodMetadata md, Method method,
                               RequestMapping annotation) {
        // Accept?????????
        String[] serverProduces = annotation.produces();
        String clientAccepts = serverProduces.length == 0
                ? null
                // ??????????????????
                : emptyToNull(serverProduces[0]);
        if (clientAccepts != null) {
            // ?????????header???
            md.template().header(ACCEPT, clientAccepts);
        }
    }

    private void parseConsumes(MethodMetadata md, Method method,
                               RequestMapping annotation) {
        // Content-Type?????????
        String[] serverConsumes = annotation.consumes();
        String clientProduces = serverConsumes.length == 0
                ? null
                : emptyToNull(serverConsumes[0]);
        if (clientProduces != null) {
            md.template().header(CONTENT_TYPE, clientProduces);
        }
    }

    private void parseHeaders(MethodMetadata md, Method method,
                              RequestMapping annotation) {
        // ?????????????????????????????????
        if (annotation.headers().length > 0) {
            for (String header : annotation.headers()) {
                int index = header.indexOf('=');
                if (!header.contains("!=") && index >= 0) {
                    // header????????????????????????????????????ConfigurableApplicationContext????????????
                    md.template().header(resolve(header.substring(0, index)),
                            resolve(header.substring(index + 1).trim()));
                }
            }
        }
    }

    private Map<Class<? extends Annotation>, AnnotatedParameterProcessor> toAnnotatedArgumentProcessorMap(
            List<AnnotatedParameterProcessor> processors) {
        Map<Class<? extends Annotation>, AnnotatedParameterProcessor> result = new HashMap<>();
        for (AnnotatedParameterProcessor processor : processors) {
            result.put(processor.getAnnotationType(), processor);
        }
        return result;
    }

    private List<AnnotatedParameterProcessor> getDefaultAnnotatedArgumentsProcessors() {

        List<AnnotatedParameterProcessor> annotatedArgumentResolvers = new ArrayList<>();

        annotatedArgumentResolvers.add(new MatrixVariableParameterProcessor());
        annotatedArgumentResolvers.add(new PathVariableParameterProcessor());
        annotatedArgumentResolvers.add(new RequestParamParameterProcessor());
        annotatedArgumentResolvers.add(new RequestHeaderParameterProcessor());
        annotatedArgumentResolvers.add(new QueryMapParameterProcessor());
        annotatedArgumentResolvers.add(new RequestPartParameterProcessor());

        return annotatedArgumentResolvers;
    }

    private Annotation synthesizeWithMethodParameterNameAsFallbackValue(
            Annotation parameterAnnotation, Method method, int parameterIndex) {
        // ????????????????????????
        Map<String, Object> annotationAttributes = AnnotationUtils
                .getAnnotationAttributes(parameterAnnotation);
        // ??????value
        Object defaultValue = AnnotationUtils.getDefaultValue(parameterAnnotation);
        // value???String
        if (defaultValue instanceof String
                // ????????????
                && defaultValue.equals(annotationAttributes.get(AnnotationUtils.VALUE))) {
            Type[] parameterTypes = method.getGenericParameterTypes();
            // ???????????????????????????
            String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            if (shouldAddParameterName(parameterIndex, parameterTypes, parameterNames)) {
                annotationAttributes.put(AnnotationUtils.VALUE,
                        // ?????????????????????value???????????? ?????????
                        parameterNames[parameterIndex]);
            }
        }
        return AnnotationUtils.synthesizeAnnotation(annotationAttributes, parameterAnnotation.annotationType(), null);
    }

    private boolean shouldAddParameterName(int parameterIndex, Type[] parameterTypes,
                                           String[] parameterNames) {
        // has a parameter name
        return parameterNames != null && parameterNames.length > parameterIndex
                // has a type
                && parameterTypes != null && parameterTypes.length > parameterIndex;
    }

    private boolean isMultipartFormData(MethodMetadata data) {
        Collection<String> contentTypes = data.template().headers()
                .get(HttpEncoding.CONTENT_TYPE);

        if (contentTypes != null && !contentTypes.isEmpty()) {
            String type = contentTypes.iterator().next();
            try {
                // multipart/form-data
                return Objects.equals(MediaType.valueOf(type), MediaType.MULTIPART_FORM_DATA);
            } catch (InvalidMediaTypeException ignored) {
                return false;
            }
        }

        return false;
    }

    /**
     * @deprecated Not used internally anymore. Will be removed in the future.
     */
    @Deprecated
    public static class ConvertingExpander implements Param.Expander {

        private final ConversionService conversionService;

        public ConvertingExpander(ConversionService conversionService) {
            this.conversionService = conversionService;
        }

        @Override
        public String expand(Object value) {
            return conversionService.convert(value, String.class);
        }

    }

    private static class ConvertingExpanderFactory {

        private final ConversionService conversionService;

        ConvertingExpanderFactory(ConversionService conversionService) {
            this.conversionService = conversionService;
        }

        Param.Expander getExpander(TypeDescriptor typeDescriptor) {
            return value -> {
                Object converted = conversionService.convert(value, typeDescriptor,
                        STRING_TYPE_DESCRIPTOR);
                return (String) converted;
            };
        }

    }

    private class SimpleAnnotatedParameterContext
            implements AnnotatedParameterProcessor.AnnotatedParameterContext {

        private final MethodMetadata methodMetadata;

        private final int parameterIndex;

        SimpleAnnotatedParameterContext(MethodMetadata methodMetadata,
                                        int parameterIndex) {
            this.methodMetadata = methodMetadata;
            this.parameterIndex = parameterIndex;
        }

        @Override
        public MethodMetadata getMethodMetadata() {
            return methodMetadata;
        }

        @Override
        public int getParameterIndex() {
            return parameterIndex;
        }

        @Override
        public void setParameterName(String name) {
            nameParam(methodMetadata, name, parameterIndex);
        }

        @Override
        public Collection<String> setTemplateParameter(String name,
                                                       Collection<String> rest) {
            return addTemplateParameter(rest, name);
        }

    }

}
