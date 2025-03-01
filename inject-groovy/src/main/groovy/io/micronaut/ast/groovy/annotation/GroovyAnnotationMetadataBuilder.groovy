/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.ast.groovy.annotation

import io.micronaut.ast.groovy.utils.AstGenericUtils
import io.micronaut.core.annotation.NonNull
import groovy.transform.CompileStatic
import io.micronaut.ast.groovy.utils.AstMessageUtils
import io.micronaut.ast.groovy.utils.ExtendedParameter
import io.micronaut.ast.groovy.visitor.GroovyVisitorContext
import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.convert.ConversionService
import io.micronaut.core.io.service.ServiceDefinition
import io.micronaut.core.io.service.SoftServiceLoader
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.core.util.CollectionUtils
import io.micronaut.core.util.StringUtils
import io.micronaut.core.value.OptionalValues
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder
import io.micronaut.inject.annotation.AnnotatedElementValidator
import io.micronaut.inject.visitor.VisitorContext
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit

import java.lang.annotation.Annotation
import java.lang.annotation.Inherited
import java.lang.annotation.Repeatable
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.reflect.Array

/**
 * Groovy implementation of {@link AbstractAnnotationMetadataBuilder}
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
class GroovyAnnotationMetadataBuilder extends AbstractAnnotationMetadataBuilder<AnnotatedNode, AnnotationNode> {
    public static Map<String, Map<? extends AnnotatedNode, Expression>> ANNOTATION_DEFAULTS = new LinkedHashMap<>()
    public static final ClassNode ANN_OVERRIDE = ClassHelper.make(Override.class)
    public static final String VALIDATOR_KEY = "io.micronaut.VALIDATOR"

    final SourceUnit sourceUnit
    final AnnotatedElementValidator elementValidator
    final CompilationUnit compilationUnit

    GroovyAnnotationMetadataBuilder(SourceUnit sourceUnit, CompilationUnit compilationUnit) {
        this.compilationUnit = compilationUnit
        this.sourceUnit = sourceUnit
        def ast = sourceUnit?.getAST()
        if (ast != null) {
            def validator = ast.getNodeMetaData(VALIDATOR_KEY)
            if (validator instanceof AnnotatedElementValidator) {
                elementValidator = (AnnotatedElementValidator) validator
            } else {
                final SoftServiceLoader<AnnotatedElementValidator> validators = SoftServiceLoader.load(AnnotatedElementValidator.class)
                final Iterator<ServiceDefinition<AnnotatedElementValidator>> i = validators.iterator()
                AnnotatedElementValidator elementValidator = null
                while (i.hasNext()) {
                    final ServiceDefinition<AnnotatedElementValidator> v = i.next()
                    if (v.isPresent()) {
                        elementValidator = v.load()
                        break
                    }
                }
                this.elementValidator = elementValidator
                ast.putNodeMetaData(VALIDATOR_KEY, elementValidator)
            }
        } else {
            this.elementValidator = null
        }

    }

    @Override
    protected boolean isValidationRequired(AnnotatedNode member) {
        if (member != null) {
            def annotations = member.getAnnotations()
            if (annotations) {
                return annotations.any { it.classNode.name.startsWith("javax.validation") }
            }
        }
        return false
    }

    @Override
    protected boolean isExcludedAnnotation(@NonNull AnnotatedNode element, @NonNull String annotationName) {
        if (element instanceof ClassNode && element.isAnnotationDefinition() && annotationName.startsWith("java.lang.annotation")) {
            return false
        } else {
            return super.isExcludedAnnotation(element, annotationName)
        }
    }

    @Override
    protected AnnotatedNode getAnnotationMember(AnnotatedNode originatingElement, CharSequence member) {
        if (originatingElement instanceof ClassNode) {
            def methods = ((ClassNode) originatingElement).getMethods(member.toString())
            if (methods) {
                return methods.iterator().next()
            }
        }
        return null
    }

    @Override
    protected RetentionPolicy getRetentionPolicy(@NonNull AnnotatedNode annotation) {
        List<AnnotationNode> annotations = annotation.getAnnotations()
        for(ann in annotations) {
            if (ann.classNode.name == Retention.name) {
                def i = ann.members.values().iterator()
                if (i.hasNext()) {
                    def expr = i.next()
                    if (expr instanceof PropertyExpression) {
                        PropertyExpression pe = (PropertyExpression) expr
                        try {
                            return RetentionPolicy.valueOf(pe.propertyAsString)
                        } catch (e) {
                            // should never happen
                            return RetentionPolicy.RUNTIME
                        }
                    }
                }
            }
        }
        return RetentionPolicy.RUNTIME
    }

    @Override
    protected AnnotatedElementValidator getElementValidator() {
        return this.elementValidator
    }

    @Override
    protected void addError(@NonNull AnnotatedNode originatingElement, @NonNull String error) {
        AstMessageUtils.error(sourceUnit, originatingElement, error)
    }

    @Override
    protected void addWarning(@NonNull AnnotatedNode originatingElement, @NonNull String warning) {
        AstMessageUtils.warning(sourceUnit, originatingElement, warning)
    }

    @Override
    protected boolean isMethodOrClassElement(AnnotatedNode element) {
        return element instanceof ClassNode || element instanceof MethodNode
    }

    @Override
    protected String getDeclaringType(@NonNull AnnotatedNode element) {
        if (element instanceof ClassNode) {
            return element.name
        }
        return element.declaringClass?.name
    }

    @Override
    protected boolean hasAnnotation(AnnotatedNode element, Class<? extends Annotation> annotation) {
        return !element.getAnnotations(ClassHelper.makeCached(annotation)).isEmpty()
    }

    @Override
    protected boolean hasAnnotation(AnnotatedNode element, String annotation) {
        for (AnnotationNode ann: element.getAnnotations()) {
            if (ann.getClassNode().getName() == annotation) {
                return true
            }
        }
        return false
    }

    @Override
    protected boolean hasAnnotations(AnnotatedNode element) {
        return CollectionUtils.isNotEmpty(element.getAnnotations())
    }

    @Override
    protected VisitorContext createVisitorContext() {
        return new GroovyVisitorContext(sourceUnit, compilationUnit)
    }

    @Override
    protected AnnotatedNode getTypeForAnnotation(AnnotationNode annotationMirror) {
        return annotationMirror.classNode
    }

    @Override
    protected String getRepeatableName(AnnotationNode annotationMirror) {
        return getRepeatableNameForType(annotationMirror.classNode)
    }

    @Override
    protected String getRepeatableNameForType(AnnotatedNode annotationType) {
        List<AnnotationNode> annotationNodes = annotationType.getAnnotations(ClassHelper.makeCached(Repeatable))
        if (annotationNodes) {
            Expression expression = annotationNodes.get(0).getMember("value")
            if (expression instanceof ClassExpression) {
                return ((ClassExpression)expression).type.name
            }
        }
        return null
    }

    @Override
    protected Optional<AnnotatedNode> getAnnotationMirror(String annotationName) {
        ClassNode cn = ClassUtils.forName(annotationName, GroovyAnnotationMetadataBuilder.classLoader).map({ Class cls -> ClassHelper.make(cls)}).orElseGet({->ClassHelper.make(annotationName)})
        if (cn.name != ClassHelper.OBJECT) {
            return Optional.of((AnnotatedNode)cn)
        } else {
            return Optional.empty()
        }
    }

    @Override
    protected String getAnnotationTypeName(AnnotationNode annotationMirror) {
        return annotationMirror.classNode.name
    }

    @Override
    protected String getElementName(AnnotatedNode element) {
        if (element instanceof ClassNode) {
            return ((ClassNode) element).getName()
        } else if (element instanceof MethodNode) {
            return ((MethodNode) element).getName()
        } else if (element instanceof FieldNode) {
            return ((FieldNode) element).getName()
        } else if (element instanceof PropertyNode) {
            return ((PropertyNode) element).getName()
        } else if (element instanceof PackageNode) {
            return ((PackageNode) element).getName()
        }
        throw new IllegalArgumentException("Cannot establish name for node type: " + element.getClass().getName())
    }

    @Override
    protected List<? extends AnnotationNode> getAnnotationsForType(AnnotatedNode element) {
        List<AnnotationNode> annotations = element.getAnnotations()
        List<AnnotationNode> expanded = new ArrayList<>(annotations.size())
        for (AnnotationNode node: annotations) {
            Expression value = node.getMember("value")
            boolean repeatable = false
            if (value != null && value instanceof ListExpression) {
                for (Expression expression: ((ListExpression) value).getExpressions()) {
                    if (expression instanceof AnnotationConstantExpression) {
                        String name = getRepeatableNameForType(expression.type)
                        if (name != null && name == node.classNode.name) {
                            repeatable = true
                            expanded.add((AnnotationNode) expression.value)
                        }
                    }
                }
            }
            if (!repeatable || node.members.size() > 1) {
                expanded.add(node)
            }
        }
        return expanded
    }

    @Override
    protected List<AnnotatedNode> buildHierarchy(AnnotatedNode element, boolean inheritTypeAnnotations, boolean declaredOnly) {
        if (declaredOnly) {
            return Collections.singletonList(element)
        } else if (element instanceof ClassNode) {
            List<AnnotatedNode> hierarchy = new ArrayList<>()
            ClassNode cn = (ClassNode) element
            hierarchy.add(cn)
            if (cn.isAnnotationDefinition()) {
                return hierarchy
            }
            populateTypeHierarchy(cn, hierarchy)
            return hierarchy.reverse()
        } else if (element instanceof MethodNode) {
            MethodNode mn = (MethodNode) element
            List<AnnotatedNode> hierarchy
            if (inheritTypeAnnotations) {
                hierarchy = buildHierarchy(mn.getDeclaringClass(), false, declaredOnly)
            } else {
                hierarchy = []
            }
            if (!mn.getAnnotations(ANN_OVERRIDE).isEmpty()) {
                hierarchy.addAll(findOverriddenMethods(mn))
            }
            hierarchy.add(mn)
            return hierarchy
        } else if (element instanceof ExtendedParameter) {
            ExtendedParameter p = (ExtendedParameter) element
            List<AnnotatedNode> hierarchy = []
            MethodNode methodNode = p.methodNode
            if (!methodNode.getAnnotations(ANN_OVERRIDE).isEmpty()) {
                int variableIdx = Arrays.asList(methodNode.parameters).indexOf(p.parameter)
                for (MethodNode overridden : findOverriddenMethods(methodNode)) {
                    hierarchy.add(new ExtendedParameter(overridden, overridden.parameters[variableIdx]))
                }
            }
            hierarchy.add(p)
            return hierarchy
        } else {
            if (element == null) {
                return []
            } else {
                return [element] as List<AnnotatedNode>
            }
        }
    }

    @Override
    protected void readAnnotationRawValues(
            AnnotatedNode originatingElement,
            String annotationName,
            AnnotatedNode member,
            String memberName,
            Object annotationValue,
            Map<CharSequence, Object> annotationValues) {
        if (!annotationValues.containsKey(memberName)) {
            def v = readAnnotationValue(originatingElement, member, memberName, annotationValue)
            if (v != null) {
                validateAnnotationValue(originatingElement, annotationName, member, memberName, v)
                annotationValues.put(memberName, v)
            }
        }
    }

    @Override
    protected Map<? extends AnnotatedNode, ?> readAnnotationDefaultValues(String annotationName, AnnotatedNode annotationType) {
        Map<String, Map<? extends AnnotatedNode, Expression>> defaults = ANNOTATION_DEFAULTS
        if (annotationType instanceof ClassNode) {
            ClassNode classNode = (ClassNode)annotationType
            if (!defaults.containsKey(annotationName)) {

                List<MethodNode> methods = new ArrayList<>(classNode.getMethods())
                Map<? extends AnnotatedNode, Expression> defaultValues = new LinkedHashMap<>()

                // TODO: Remove this branch of the code after upgrading to Groovy 3.0
                // https://issues.apache.org/jira/browse/GROOVY-8696
                if (classNode.isResolved()) {
                    Class resolved = classNode.getTypeClass()
                    for (MethodNode method: methods) {
                        try {
                            def defaultValue = resolved.getDeclaredMethod(method.getName()).defaultValue
                            if (defaultValue != null) {
                                if (defaultValue instanceof Class) {
                                    defaultValues.put(method, new ClassExpression(ClassHelper.makeCached((Class)defaultValue)))
                                } else {
                                    if (defaultValue instanceof String) {
                                        if (StringUtils.isNotEmpty((String)defaultValue)) {
                                            defaultValues.put(method, new ConstantExpression(defaultValue))
                                        }
                                    } else {
                                        defaultValues.put(method, new ConstantExpression(defaultValue))
                                    }
                                }
                            }
                        } catch (NoSuchMethodError e) {
                            // method no longer exists alias annotation
                            // ignore and continue
                        }
                    }
                } else {
                    for (MethodNode method: methods) {
                        Statement stmt = method.code
                        def expression = null
                        if (stmt instanceof ReturnStatement) {
                            expression = ((ReturnStatement) stmt).expression
                        } else if (stmt instanceof ExpressionStatement) {
                            expression = ((ExpressionStatement) stmt).expression
                        }
                        if (expression instanceof ConstantExpression) {
                            ConstantExpression ce = (ConstantExpression) expression
                            def v = ce.value
                            if (v != null) {
                                if (v instanceof String) {
                                    if (StringUtils.isNotEmpty((String)v)) {
                                        defaultValues.put(method, new ConstantExpression(v))
                                    }
                                } else {
                                    defaultValues.put(method, (Expression)expression)
                                }
                            }
                        }
                    }
                }

                defaults.put(annotationName, defaultValues)
            }
        }

        return defaults.get(annotationName) ?: Collections.emptyMap()
    }

    @Override
    protected boolean isInheritedAnnotation(@NonNull AnnotationNode annotationMirror) {
        return annotationMirror?.classNode?.annotations?.any { it?.classNode?.name == Inherited.name }
    }

    @Override
    protected boolean isInheritedAnnotationType(@NonNull AnnotatedNode annotationType) {
        return annotationType?.annotations?.any { it?.classNode?.name == Inherited.name }
    }

    @Override
    protected Map<? extends AnnotatedNode, ?> readAnnotationDefaultValues(AnnotationNode annotationMirror) {
        ClassNode classNode = annotationMirror.classNode
        String annotationName = classNode.name
        return readAnnotationDefaultValues(annotationName, classNode)
    }

    @Override
    protected Object readAnnotationValue(AnnotatedNode originatingElement, AnnotatedNode member, String memberName, Object annotationValue) {
        if (annotationValue instanceof ConstantExpression) {
            if (annotationValue instanceof AnnotationConstantExpression) {
                AnnotationConstantExpression ann = (AnnotationConstantExpression) annotationValue
                AnnotationNode value = (AnnotationNode) ann.getValue()
                if (member instanceof MethodNode && member.returnType.isArray()) {
                    return [readNestedAnnotationValue(originatingElement, value)] as AnnotationValue[]
                } else {
                    return readNestedAnnotationValue(originatingElement, value)
                }
            } else {
                return ((ConstantExpression) annotationValue).value
            }

        } else if (annotationValue instanceof PropertyExpression) {
            PropertyExpression pe = (PropertyExpression) annotationValue
            if (pe.objectExpression instanceof ClassExpression) {
                ClassExpression ce = (ClassExpression) pe.objectExpression
                ClassNode propertyType = ce.type
                if (propertyType.isEnum()) {
                    return pe.getPropertyAsString()
                } else {
                    if (propertyType.isResolved()) {
                        Class typeClass = propertyType.typeClass
                        try {
                            def value = typeClass[pe.propertyAsString]
                            if (value != null) {
                                return value
                            }
                        } catch (e) {
                            // ignore
                        }
                    }
                }
            }
        } else if (annotationValue instanceof ClassExpression) {
            return new AnnotationClassValue(((ClassExpression) annotationValue).type.name)
        } else if (annotationValue instanceof ListExpression) {
            ListExpression le = (ListExpression) annotationValue
            List converted = []
            Class arrayType = Object.class
            for (exp in le.expressions) {
                if (exp instanceof PropertyExpression) {
                    PropertyExpression propertyExpression = (PropertyExpression) exp
                    Expression valueExpression = propertyExpression.getProperty()
                    Expression objectExpression = propertyExpression.getObjectExpression()
                    if (valueExpression instanceof ConstantExpression && objectExpression instanceof ClassExpression) {
                        Object value = ((ConstantExpression) valueExpression).value
                        if (value != null) {
                            if (value instanceof CharSequence) {
                                value = value.toString()
                            }
                            ClassNode enumType = ((ClassExpression) objectExpression).type
                            if (enumType.isResolved()) {
                                arrayType = enumType.typeClass
                            } else {
                                arrayType = String.class
                            }
                            converted.add(value)
                        }
                    }
                }
                if (exp instanceof AnnotationConstantExpression) {
                    arrayType = AnnotationValue
                    AnnotationConstantExpression ann = (AnnotationConstantExpression) exp
                    AnnotationNode value = (AnnotationNode) ann.getValue()
                    converted.add(readNestedAnnotationValue(originatingElement, value))
                } else if (exp instanceof ConstantExpression) {
                    Object value = ((ConstantExpression) exp).value
                    if(value != null) {
                        if(value instanceof CharSequence) {
                            value = value.toString()
                        }
                        arrayType = value.getClass()
                        converted.add(value)
                    }
                } else if (exp instanceof ClassExpression) {
                    arrayType = AnnotationClassValue
                    ClassExpression classExp = ((ClassExpression) exp)
                    String typeName
                    if (classExp.type.isArray()) {
                        typeName = "[L" + classExp.type.componentType.name + ";"
                    } else {
                        typeName = classExp.type.name
                    }
                    converted.add(new AnnotationClassValue<>(typeName))
                }
            }
            // for some reason this is necessary to produce correct array type in Groovy
            return ConversionService.SHARED.convert(converted, Array.newInstance(arrayType, 0).getClass()).orElse(null)
        } else if (annotationValue instanceof VariableExpression) {
            VariableExpression ve = (VariableExpression) annotationValue
            Variable variable = ve.accessedVariable
            if (variable != null && variable.hasInitialExpression()) {
                return readAnnotationValue(originatingElement, member, memberName, variable.getInitialExpression())
            }
        } else if (annotationValue != null) {
            if (ClassUtils.isJavaLangType(annotationValue.getClass())) {
                return annotationValue
            }
        }
        return null
    }

    @Override
    protected Map<? extends AnnotatedNode, ?> readAnnotationRawValues(AnnotationNode annotationMirror) {
        Map<String, Expression> members = annotationMirror.getMembers()
        Map<? extends AnnotatedNode, Object> values = [:]
        ClassNode annotationClassNode = annotationMirror.classNode
        for (m in members) {
            values.put(annotationClassNode.getMethods(m.key)[0], m.value)
        }
        return values
    }

    @Override
    protected OptionalValues<?> getAnnotationValues(AnnotatedNode originatingElement, AnnotatedNode member, Class<?> annotationType) {
        if (member != null) {
            def anns = member.getAnnotations(ClassHelper.make(annotationType))
            if (!anns.isEmpty()) {
                AnnotationNode ann = anns[0]
                Map<CharSequence, Object> converted = new LinkedHashMap<>();
                for (annMember in ann.members) {
                    readAnnotationRawValues(originatingElement, annotationType.name, member, annMember.key, annMember.value, converted)
                }
                return OptionalValues.of(Object.class, converted)
            }
        }
        return OptionalValues.empty()
    }

    @Override
    protected String getAnnotationMemberName(AnnotatedNode member) {
        return ((MethodNode) member).getName()
    }

    private void populateTypeHierarchy(ClassNode classNode, List<AnnotatedNode> hierarchy) {
        while (classNode != null) {
            ClassNode[] interfaces = classNode.getInterfaces()
            for (ClassNode anInterface : interfaces) {
                if (!hierarchy.contains(anInterface) && anInterface.name != GroovyObject.name) {
                    hierarchy.add(anInterface)
                    populateTypeHierarchy(anInterface, hierarchy)
                }
            }
            classNode = classNode.getSuperClass()
            if (classNode != null) {
                if (classNode == ClassHelper.OBJECT_TYPE || classNode.name == Script.name || classNode.name == GroovyObjectSupport.name) {
                    break
                } else {
                    hierarchy.add(classNode)
                }
            } else {
                break
            }
        }
    }

    private List<MethodNode> findOverriddenMethods(MethodNode methodNode) {
        List<MethodNode> overriddenMethods = []
        ClassNode classNode = methodNode.getDeclaringClass()

        String methodName = methodNode.name
        Map<String, Map<String, ClassNode>> genericsInfo = AstGenericUtils.buildAllGenericElementInfo(classNode, createVisitorContext())

        classLoop:
        while (classNode != null && classNode.name != Object.name) {
            for (i in classNode.getAllInterfaces()) {
                for (MethodNode parent: i.getMethods(methodName)) {
                    if (methodOverrides(methodNode, parent, genericsInfo.get(i.name))) {
                        overriddenMethods.add(parent)
                    }
                }
            }
            classNode = classNode.superClass
            if (classNode != null && classNode.name != Object.name) {

                for (MethodNode parent: classNode.getMethods(methodName)) {
                    if (methodOverrides(methodNode, parent, genericsInfo.get(classNode.name))) {
                        if (!parent.isPrivate()) {
                            overriddenMethods.add(parent)
                        }
                        if (parent.getAnnotations(ANN_OVERRIDE).isEmpty()) {
                            break classLoop
                        }
                    }
                }
            }
        }
        return overriddenMethods
    }

    private boolean methodOverrides(MethodNode child,
                                    MethodNode parent,
                                    Map<String, ClassNode> genericsSpec) {
        Parameter[] childParameters = child.parameters
        Parameter[] parentParameters = parent.parameters
        if (childParameters.length == parentParameters.length) {
            for (int i = 0, n = childParameters.length; i < n; i += 1) {
                ClassNode aType = childParameters[i].getType()
                ClassNode bType = parentParameters[i].getType()

                if (aType != bType) {
                    if (bType.isGenericsPlaceHolder() && genericsSpec != null) {
                        def classNode = genericsSpec.get(bType.getUnresolvedName())
                        if (!classNode || aType != classNode) {
                            return false
                        }
                    } else {
                        return false
                    }
                }
            }
            return true
        }
        return false
    }
}
