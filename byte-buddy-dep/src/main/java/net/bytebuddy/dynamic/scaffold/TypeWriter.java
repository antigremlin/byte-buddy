package net.bytebuddy.dynamic.scaffold;

import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.ClassVisitorWrapper;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodRebaseResolver;
import net.bytebuddy.instrumentation.Instrumentation;
import net.bytebuddy.instrumentation.LoadedTypeInitializer;
import net.bytebuddy.instrumentation.attribute.FieldAttributeAppender;
import net.bytebuddy.instrumentation.attribute.MethodAttributeAppender;
import net.bytebuddy.instrumentation.attribute.TypeAttributeAppender;
import net.bytebuddy.instrumentation.attribute.annotation.AnnotationAppender;
import net.bytebuddy.instrumentation.field.FieldDescription;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.MethodList;
import net.bytebuddy.instrumentation.method.ParameterDescription;
import net.bytebuddy.instrumentation.method.ParameterList;
import net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import net.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.MethodInvocation;
import net.bytebuddy.instrumentation.type.InstrumentedType;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.instrumentation.type.TypeList;
import net.bytebuddy.instrumentation.type.auxiliary.AuxiliaryType;
import net.bytebuddy.utility.RandomString;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.utility.ByteBuddyCommons.join;

/**
 * A type writer is a utility for writing an actual class file using the ASM library.
 *
 * @param <T> The best known loaded type for the dynamically created type.
 */
public interface TypeWriter<T> {

    /**
     * Creates the dynamic type that is described by this type writer.
     *
     * @return An unloaded dynamic type that describes the created type.
     */
    DynamicType.Unloaded<T> make();

    /**
     * An field pool that allows a lookup for how to implement a field.
     */
    interface FieldPool {

        /**
         * Returns the field attribute appender that matches a given field description or a default field
         * attribute appender if no appender was registered for the given field.
         *
         * @param fieldDescription The field description of interest.
         * @return The registered field attribute appender for the given field or the default appender if no such
         * appender was found.
         */
        Entry target(FieldDescription fieldDescription);

        /**
         * An entry of a field pool that describes how a field is implemented.
         *
         * @see net.bytebuddy.dynamic.scaffold.TypeWriter.FieldPool
         */
        interface Entry {

            /**
             * Returns the field attribute appender for a given field.
             *
             * @return The attribute appender to be applied on the given field.
             */
            FieldAttributeAppender getFieldAppender();

            /**
             * Returns the default value for the field that is represented by this entry. This value might be
             * {@code null} if no such value is set.
             *
             * @return The default value for the field that is represented by this entry.
             */
            Object getDefaultValue();

            /**
             * Writes this entry to a given class visitor.
             *
             * @param classVisitor     The class visitor to which this entry is to be written to.
             * @param fieldDescription A description of the field that is to be written.
             */
            void apply(ClassVisitor classVisitor, FieldDescription fieldDescription);

            /**
             * A default implementation of a compiled field registry that simply returns a no-op
             * {@link net.bytebuddy.instrumentation.attribute.FieldAttributeAppender.Factory}
             * for any field.
             */
            enum NoOp implements Entry {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public FieldAttributeAppender getFieldAppender() {
                    return FieldAttributeAppender.NoOp.INSTANCE;
                }

                @Override
                public Object getDefaultValue() {
                    return null;
                }

                @Override
                public void apply(ClassVisitor classVisitor, FieldDescription fieldDescription) {
                    classVisitor.visitField(fieldDescription.getModifiers(),
                            fieldDescription.getInternalName(),
                            fieldDescription.getDescriptor(),
                            fieldDescription.getGenericSignature(),
                            null).visitEnd();
                }

                @Override
                public String toString() {
                    return "TypeWriter.FieldPool.Entry.NoOp." + name();
                }
            }

            /**
             * A simple entry that creates a specific
             * {@link net.bytebuddy.instrumentation.attribute.FieldAttributeAppender.Factory}
             * for any field.
             */
            class Simple implements Entry {

                /**
                 * The field attribute appender factory that is represented by this entry.
                 */
                private final FieldAttributeAppender attributeAppender;

                /**
                 * The field's default value or {@code null} if no default value is set.
                 */
                private final Object defaultValue;

                /**
                 * Creates a new simple entry for a given attribute appender factory.
                 *
                 * @param attributeAppender The attribute appender to be returned.
                 * @param defaultValue      The field's default value or {@code null} if no default value is
                 *                          set.
                 */
                public Simple(FieldAttributeAppender attributeAppender, Object defaultValue) {
                    this.attributeAppender = attributeAppender;
                    this.defaultValue = defaultValue;
                }

                @Override
                public FieldAttributeAppender getFieldAppender() {
                    return attributeAppender;
                }

                @Override
                public Object getDefaultValue() {
                    return defaultValue;
                }

                @Override
                public void apply(ClassVisitor classVisitor, FieldDescription fieldDescription) {
                    FieldVisitor fieldVisitor = classVisitor.visitField(fieldDescription.getModifiers(),
                            fieldDescription.getInternalName(),
                            fieldDescription.getDescriptor(),
                            fieldDescription.getGenericSignature(),
                            defaultValue);
                    attributeAppender.apply(fieldVisitor, fieldDescription);
                    fieldVisitor.visitEnd();
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other)
                        return true;
                    if (other == null || getClass() != other.getClass())
                        return false;
                    Simple simple = (Simple) other;
                    return attributeAppender.equals(simple.attributeAppender)
                            && !(defaultValue != null ?
                            !defaultValue.equals(simple.defaultValue) :
                            simple.defaultValue != null);
                }

                @Override
                public int hashCode() {
                    return 31 * attributeAppender.hashCode() + (defaultValue != null ? defaultValue.hashCode() : 0);
                }

                @Override
                public String toString() {
                    return "TypeWriter.FieldPool.Entry.Simple{" +
                            "attributeAppenderFactory=" + attributeAppender +
                            ", defaultValue=" + defaultValue +
                            '}';
                }
            }
        }
    }

    /**
     * An method pool that allows a lookup for how to implement a method.
     */
    interface MethodPool {

        /**
         * Looks up a handler entry for a given method.
         *
         * @param methodDescription The method being processed.
         * @return A handler entry for the given method.
         */
        Entry target(MethodDescription methodDescription);

        /**
         * An entry of a method pool that describes how a method is implemented.
         *
         * @see net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool
         */
        interface Entry {

            /**
             * Returns the sort of this method instrumentation.
             *
             * @return The sort of this method instrumentation.
             */
            Sort getSort();

            /**
             * Prepends the given method appender to this entry.
             *
             * @param byteCodeAppender The byte code appender to prepend.
             * @return This entry with the given code prepended.
             */
            Entry prepend(ByteCodeAppender byteCodeAppender);

            /**
             * Applies this method entry. This method can always be called and might be a no-op.
             *
             * @param classVisitor           The class visitor to which this entry should be applied.
             * @param instrumentationContext The instrumentation context to which this entry should be applied.
             * @param methodDescription      The method description of the instrumented method.
             */
            void apply(ClassVisitor classVisitor, Instrumentation.Context instrumentationContext, MethodDescription methodDescription);

            /**
             * Applies the head of this entry. Applying an entry is only possible if a method is defined, i.e. the sort of this entry is not
             * {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool.Entry.Sort#SKIP}.
             *
             * @param methodVisitor     The method visitor to which this entry should be applied.
             * @param methodDescription The method description of the instrumented method.
             */
            void applyHead(MethodVisitor methodVisitor, MethodDescription methodDescription);

            /**
             * Applies the body of this entry. Applying the body of an entry is only possible if a method is implemented, i.e. the sort of this
             * entry is {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool.Entry.Sort#IMPLEMENT}.
             *
             * @param methodVisitor          The method visitor to which this entry should be applied.
             * @param instrumentationContext The instrumentation context to which this entry should be applied.
             * @param methodDescription      The method description of the instrumented method.
             */
            void applyBody(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext, MethodDescription methodDescription);

            /**
             * The sort of an entry.
             */
            enum Sort {

                /**
                 * Describes a method that should not be implemented or retained in its original state.
                 */
                SKIP(false, false),

                /**
                 * Describes a method that should be defined but is abstract or native, i.e. does not define any byte code.
                 */
                DEFINE(true, false),

                /**
                 * Describes a method that is implemented in byte code.
                 */
                IMPLEMENT(true, true);

                /**
                 * Indicates if this sort defines a method, with or without byte code.
                 */
                private final boolean define;

                /**
                 * Indicates if this sort defines byte code.
                 */
                private final boolean implement;

                /**
                 * Creates a new sort.
                 *
                 * @param define    Indicates if this sort defines a method, with or without byte code.
                 * @param implement Indicates if this sort defines byte code.
                 */
                Sort(boolean define, boolean implement) {
                    this.define = define;
                    this.implement = implement;
                }

                /**
                 * Indicates if this sort defines a method, with or without byte code.
                 *
                 * @return {@code true} if this sort defines a method, with or without byte code.
                 */
                public boolean isDefined() {
                    return define;
                }

                /**
                 * Indicates if this sort defines byte code.
                 *
                 * @return {@code true} if this sort defines byte code.
                 */
                public boolean isImplemented() {
                    return implement;
                }

                @Override
                public String toString() {
                    return "TypeWriter.MethodPool.Entry.Sort." + name();
                }
            }

            /**
             * A base implementation of an abstract entry that defines a method.
             */
            abstract class AbstractDefiningEntry implements Entry {

                @Override
                public void apply(ClassVisitor classVisitor, Instrumentation.Context instrumentationContext, MethodDescription methodDescription) {
                    MethodVisitor methodVisitor = classVisitor.visitMethod(methodDescription.getAdjustedModifiers(getSort().isImplemented()),
                            methodDescription.getInternalName(),
                            methodDescription.getDescriptor(),
                            methodDescription.getGenericSignature(),
                            methodDescription.getExceptionTypes().toInternalNames());
                    ParameterList parameterList = methodDescription.getParameters();
                    if (parameterList.hasExplicitMetaData()) {
                        for (ParameterDescription parameterDescription : parameterList) {
                            methodVisitor.visitParameter(parameterDescription.getName(), parameterDescription.getModifiers());
                        }
                    }
                    applyHead(methodVisitor, methodDescription);
                    applyBody(methodVisitor, instrumentationContext, methodDescription);
                    methodVisitor.visitEnd();
                }
            }

            /**
             * A canonical implementation of a skipped method.
             */
            enum ForSkippedMethod implements Entry {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public void apply(ClassVisitor classVisitor,
                                  Instrumentation.Context instrumentationContext,
                                  MethodDescription methodDescription) {
                    /* do nothing */
                }

                @Override
                public void applyBody(MethodVisitor methodVisitor,
                                      Instrumentation.Context instrumentationContext,
                                      MethodDescription methodDescription) {
                    throw new IllegalStateException("Cannot apply headless implementation for method that should be skipped");
                }

                @Override
                public void applyHead(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    throw new IllegalStateException("Cannot apply headless implementation for method that should be skipped");
                }

                @Override
                public Sort getSort() {
                    return Sort.SKIP;
                }

                @Override
                public Entry prepend(ByteCodeAppender byteCodeAppender) {
                    throw new IllegalStateException("Cannot prepend code to non-implemented method");
                }

                @Override
                public String toString() {
                    return "TypeWriter.MethodPool.Entry.Skip." + name();
                }
            }

            /**
             * Describes an entry that defines a method as byte code.
             */
            class ForImplementation extends AbstractDefiningEntry {

                /**
                 * The byte code appender to apply.
                 */
                private final ByteCodeAppender byteCodeAppender;

                /**
                 * The method attribute appender to apply.
                 */
                private final MethodAttributeAppender methodAttributeAppender;

                /**
                 * Creates a new entry for a method that defines a method as byte code.
                 *
                 * @param byteCodeAppender        The byte code appender to apply.
                 * @param methodAttributeAppender The method attribute appender to apply.
                 */
                public ForImplementation(ByteCodeAppender byteCodeAppender, MethodAttributeAppender methodAttributeAppender) {
                    this.byteCodeAppender = byteCodeAppender;
                    this.methodAttributeAppender = methodAttributeAppender;
                }

                @Override
                public void applyHead(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    /* do nothing */
                }

                @Override
                public void applyBody(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext, MethodDescription methodDescription) {
                    methodAttributeAppender.apply(methodVisitor, methodDescription);
                    methodVisitor.visitCode();
                    ByteCodeAppender.Size size = byteCodeAppender.apply(methodVisitor, instrumentationContext, methodDescription);
                    methodVisitor.visitMaxs(size.getOperandStackSize(), size.getLocalVariableSize());
                }

                @Override
                public Entry prepend(ByteCodeAppender byteCodeAppender) {
                    return new ForImplementation(new ByteCodeAppender.Compound(byteCodeAppender, this.byteCodeAppender), methodAttributeAppender);
                }

                @Override
                public Sort getSort() {
                    return Sort.IMPLEMENT;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && byteCodeAppender.equals(((ForImplementation) other).byteCodeAppender)
                            && methodAttributeAppender.equals(((ForImplementation) other).methodAttributeAppender);
                }

                @Override
                public int hashCode() {
                    return 31 * byteCodeAppender.hashCode() + methodAttributeAppender.hashCode();
                }

                @Override
                public String toString() {
                    return "TypeWriter.MethodPool.Entry.ForImplementation{" +
                            "byteCodeAppender=" + byteCodeAppender +
                            ", methodAttributeAppender=" + methodAttributeAppender +
                            '}';
                }
            }

            /**
             * Describes an entry that defines a method but without byte code and without an annotation value.
             */
            class ForAbstractMethod extends AbstractDefiningEntry {

                /**
                 * The method attribute appender to apply.
                 */
                private final MethodAttributeAppender methodAttributeAppender;

                /**
                 * Creates a new entry for a method that is defines but does not append byte code, i.e. is native or abstract.
                 * @param methodAttributeAppender The method attribute appender to apply.
                 */
                public ForAbstractMethod(MethodAttributeAppender methodAttributeAppender) {
                    this.methodAttributeAppender = methodAttributeAppender;
                }

                @Override
                public Sort getSort() {
                    return Sort.DEFINE;
                }

                @Override
                public void applyHead(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    /* do nothing */
                }

                @Override
                public void applyBody(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext, MethodDescription methodDescription) {
                    methodAttributeAppender.apply(methodVisitor, methodDescription);
                }

                @Override
                public Entry prepend(ByteCodeAppender byteCodeAppender) {
                    throw new IllegalStateException("Cannot prepend code to abstract method");
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && methodAttributeAppender.equals(((ForAbstractMethod) other).methodAttributeAppender);
                }

                @Override
                public int hashCode() {
                    return methodAttributeAppender.hashCode();
                }

                @Override
                public String toString() {
                    return "TypeWriter.MethodPool.Entry.ForAbstractMethod{" +
                            "methodAttributeAppender=" + methodAttributeAppender +
                            '}';
                }
            }

            /**
             * Describes an entry that defines a method with a default annotation value.
             */
            class ForAnnotationDefaultValue extends AbstractDefiningEntry {

                /**
                 * The annotation value to define.
                 */
                private final Object annotationValue;

                /**
                 * The method attribute appender to apply.
                 */
                private final MethodAttributeAppender methodAttributeAppender;

                /**
                 * Creates a new entry for defining a method with a default annotation value.
                 * @param annotationValue The annotation value to define.
                 * @param methodAttributeAppender The method attribute appender to apply.
                 */
                public ForAnnotationDefaultValue(Object annotationValue, MethodAttributeAppender methodAttributeAppender) {
                    this.annotationValue = annotationValue;
                    this.methodAttributeAppender = methodAttributeAppender;
                }

                @Override
                public Sort getSort() {
                    return Sort.DEFINE;
                }

                @Override
                public void applyHead(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    AnnotationAppender.Default.apply(methodVisitor.visitAnnotationDefault(),
                            methodDescription.getReturnType(),
                            AnnotationAppender.NO_NAME,
                            annotationValue);
                }

                @Override
                public void applyBody(MethodVisitor methodVisitor,
                                      Instrumentation.Context instrumentationContext,
                                      MethodDescription methodDescription) {
                    methodAttributeAppender.apply(methodVisitor, methodDescription);
                }

                @Override
                public Entry prepend(ByteCodeAppender byteCodeAppender) {
                    throw new IllegalStateException("Cannot prepend code to method that defines a default annotation value");
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForAnnotationDefaultValue that = (ForAnnotationDefaultValue) other;
                    return annotationValue.equals(that.annotationValue) && methodAttributeAppender.equals(that.methodAttributeAppender);

                }

                @Override
                public int hashCode() {
                    int result = annotationValue.hashCode();
                    result = 31 * result + methodAttributeAppender.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "TypeWriter.MethodPool.Entry.ForAnnotationDefaultValue{" +
                            "annotationValue=" + annotationValue +
                            ", methodAttributeAppender=" + methodAttributeAppender +
                            '}';
                }
            }
        }
    }

    /**
     * A default implementation of a {@link net.bytebuddy.dynamic.scaffold.TypeWriter}.
     *
     * @param <S> The best known loaded type for the dynamically created type.
     */
    abstract class Default<S> implements TypeWriter<S> {

        /**
         * A flag for ASM not to automatically compute any information such as operand stack sizes and stack map frames.
         */
        protected static final int ASM_MANUAL_FLAG = 0;

        /**
         * The ASM API version to use.
         */
        protected static final int ASM_API_VERSION = Opcodes.ASM5;

        /**
         * The instrumented type that is to be written.
         */
        protected final TypeDescription instrumentedType;

        /**
         * The loaded type initializer of the instrumented type.
         */
        protected final LoadedTypeInitializer loadedTypeInitializer;

        /**
         * The type initializer of the instrumented type.
         */
        protected final InstrumentedType.TypeInitializer typeInitializer;

        /**
         * A list of explicit auxiliary types that are to be added to the created dynamic type.
         */
        protected final List<DynamicType> explicitAuxiliaryTypes;

        /**
         * The class file version of the written type.
         */
        protected final ClassFileVersion classFileVersion;

        /**
         * A naming strategy that is used for naming auxiliary types.
         */
        protected final AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy;

        /**
         * A class visitor wrapper to apply during instrumentation.
         */
        protected final ClassVisitorWrapper classVisitorWrapper;

        /**
         * The type attribute appender to apply.
         */
        protected final TypeAttributeAppender attributeAppender;

        /**
         * The field pool to be used for instrumenting fields.
         */
        protected final FieldPool fieldPool;

        /**
         * The method pool to be used for instrumenting methods.
         */
        protected final MethodPool methodPool;

        /**
         * A list of all instrumented methods.
         */
        protected final MethodList instrumentedMethods;

        /**
         * Creates a type writer for creating a new type.
         *
         * @param methodRegistry              The method registry to use for creating the type.
         * @param fieldPool                   The field pool to use.
         * @param auxiliaryTypeNamingStrategy A naming strategy for naming auxiliary types.
         * @param classVisitorWrapper         The class visitor wrapper to apply when creating the type.
         * @param attributeAppender           The attribute appender to use.
         * @param classFileVersion            The class file version of the created type.
         * @param <U>                         The best known loaded type for the dynamically created type.
         * @return An appropriate type writer.
         */
        public static <U> TypeWriter<U> forCreation(MethodRegistry.Compiled methodRegistry,
                                                    FieldPool fieldPool,
                                                    AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                                                    ClassVisitorWrapper classVisitorWrapper,
                                                    TypeAttributeAppender attributeAppender,
                                                    ClassFileVersion classFileVersion) {
            return new ForCreation<U>(methodRegistry.getInstrumentedType(),
                    methodRegistry.getLoadedTypeInitializer(),
                    methodRegistry.getTypeInitializer(),
                    Collections.<DynamicType>emptyList(),
                    classFileVersion,
                    auxiliaryTypeNamingStrategy,
                    classVisitorWrapper,
                    attributeAppender,
                    fieldPool,
                    methodRegistry,
                    methodRegistry.getInstrumentedMethods());
        }

        /**
         * Creates a type writer for creating a new type.
         *
         * @param methodRegistry              The method registry to use for creating the type.
         * @param fieldPool                   The field pool to use.
         * @param auxiliaryTypeNamingStrategy A naming strategy for naming auxiliary types.
         * @param classVisitorWrapper         The class visitor wrapper to apply when creating the type.
         * @param attributeAppender           The attribute appender to use.
         * @param classFileVersion            The minimum class file version of the created type.
         * @param classFileLocator            The class file locator to use.
         * @param methodRebaseResolver        The method rebase resolver to use.
         * @param targetType                  The target type that is to be rebased.
         * @param <U>                         The best known loaded type for the dynamically created type.
         * @return An appropriate type writer.
         */
        public static <U> TypeWriter<U> forRebasing(MethodRegistry.Compiled methodRegistry,
                                                    FieldPool fieldPool,
                                                    AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                                                    ClassVisitorWrapper classVisitorWrapper,
                                                    TypeAttributeAppender attributeAppender,
                                                    ClassFileVersion classFileVersion,
                                                    ClassFileLocator classFileLocator,
                                                    TypeDescription targetType,
                                                    MethodRebaseResolver methodRebaseResolver) {
            return new ForInlining<U>(methodRegistry.getInstrumentedType(),
                    methodRegistry.getLoadedTypeInitializer(),
                    methodRegistry.getTypeInitializer(),
                    methodRebaseResolver.getAuxiliaryTypes(),
                    classFileVersion,
                    auxiliaryTypeNamingStrategy,
                    classVisitorWrapper,
                    attributeAppender,
                    fieldPool,
                    methodRegistry,
                    methodRegistry.getInstrumentedMethods(),
                    classFileLocator,
                    targetType,
                    methodRebaseResolver);
        }

        /**
         * Creates a type writer for creating a new type.
         *
         * @param methodRegistry              The method registry to use for creating the type.
         * @param fieldPool                   The field pool to use.
         * @param auxiliaryTypeNamingStrategy A naming strategy for naming auxiliary types.
         * @param classVisitorWrapper         The class visitor wrapper to apply when creating the type.
         * @param attributeAppender           The attribute appender to use.
         * @param classFileVersion            The minimum class file version of the created type.
         * @param classFileLocator            The class file locator to use.
         * @param targetType                  The target type that is to be rebased.
         * @param <U>                         The best known loaded type for the dynamically created type.
         * @return An appropriate type writer.
         */
        public static <U> TypeWriter<U> forRedefinition(MethodRegistry.Compiled methodRegistry,
                                                        FieldPool fieldPool,
                                                        AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                                                        ClassVisitorWrapper classVisitorWrapper,
                                                        TypeAttributeAppender attributeAppender,
                                                        ClassFileVersion classFileVersion,
                                                        ClassFileLocator classFileLocator,
                                                        TypeDescription targetType) {
            return new ForInlining<U>(methodRegistry.getInstrumentedType(),
                    methodRegistry.getLoadedTypeInitializer(),
                    methodRegistry.getTypeInitializer(),
                    Collections.<DynamicType>emptyList(),
                    classFileVersion,
                    auxiliaryTypeNamingStrategy,
                    classVisitorWrapper,
                    attributeAppender,
                    fieldPool,
                    methodRegistry,
                    methodRegistry.getInstrumentedMethods(),
                    classFileLocator,
                    targetType,
                    MethodRebaseResolver.Disabled.INSTANCE);
        }

        /**
         * Creates a new default type writer.
         *
         * @param instrumentedType            The instrumented type that is to be written.
         * @param loadedTypeInitializer       The loaded type initializer of the instrumented type.
         * @param typeInitializer             The type initializer of the instrumented type.
         * @param explicitAuxiliaryTypes      A list of explicit auxiliary types that are to be added to the created dynamic type.
         * @param classFileVersion            The class file version of the written type.
         * @param auxiliaryTypeNamingStrategy A naming strategy that is used for naming auxiliary types.
         * @param classVisitorWrapper         A class visitor wrapper to apply during instrumentation.
         * @param attributeAppender           The type attribute appender to apply.
         * @param fieldPool                   The field pool to be used for instrumenting fields.
         * @param methodPool                  The method pool to be used for instrumenting methods.
         * @param instrumentedMethods         A list of all instrumented methods.
         */
        protected Default(TypeDescription instrumentedType,
                          LoadedTypeInitializer loadedTypeInitializer,
                          InstrumentedType.TypeInitializer typeInitializer,
                          List<DynamicType> explicitAuxiliaryTypes,
                          ClassFileVersion classFileVersion,
                          AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                          ClassVisitorWrapper classVisitorWrapper,
                          TypeAttributeAppender attributeAppender,
                          FieldPool fieldPool,
                          MethodPool methodPool,
                          MethodList instrumentedMethods) {
            this.instrumentedType = instrumentedType;
            this.loadedTypeInitializer = loadedTypeInitializer;
            this.typeInitializer = typeInitializer;
            this.explicitAuxiliaryTypes = explicitAuxiliaryTypes;
            this.classFileVersion = classFileVersion;
            this.auxiliaryTypeNamingStrategy = auxiliaryTypeNamingStrategy;
            this.classVisitorWrapper = classVisitorWrapper;
            this.attributeAppender = attributeAppender;
            this.fieldPool = fieldPool;
            this.methodPool = methodPool;
            this.instrumentedMethods = instrumentedMethods;
        }

        @Override
        public DynamicType.Unloaded<S> make() {
            Instrumentation.Context.ExtractableView instrumentationContext = new Instrumentation.Context.Default(instrumentedType,
                    auxiliaryTypeNamingStrategy,
                    typeInitializer,
                    classFileVersion);
            return new DynamicType.Default.Unloaded<S>(instrumentedType,
                    create(instrumentationContext),
                    loadedTypeInitializer,
                    join(explicitAuxiliaryTypes, instrumentationContext.getRegisteredAuxiliaryTypes()));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Default<?> aDefault = (Default<?>) other;
            return instrumentedType.equals(aDefault.instrumentedType)
                    && loadedTypeInitializer.equals(aDefault.loadedTypeInitializer)
                    && typeInitializer.equals(aDefault.typeInitializer)
                    && explicitAuxiliaryTypes.equals(aDefault.explicitAuxiliaryTypes)
                    && classFileVersion.equals(aDefault.classFileVersion)
                    && auxiliaryTypeNamingStrategy.equals(aDefault.auxiliaryTypeNamingStrategy)
                    && classVisitorWrapper.equals(aDefault.classVisitorWrapper)
                    && attributeAppender.equals(aDefault.attributeAppender)
                    && fieldPool.equals(aDefault.fieldPool)
                    && methodPool.equals(aDefault.methodPool)
                    && instrumentedMethods.equals(aDefault.instrumentedMethods);
        }

        @Override
        public int hashCode() {
            int result = instrumentedType.hashCode();
            result = 31 * result + loadedTypeInitializer.hashCode();
            result = 31 * result + typeInitializer.hashCode();
            result = 31 * result + explicitAuxiliaryTypes.hashCode();
            result = 31 * result + classFileVersion.hashCode();
            result = 31 * result + auxiliaryTypeNamingStrategy.hashCode();
            result = 31 * result + classVisitorWrapper.hashCode();
            result = 31 * result + attributeAppender.hashCode();
            result = 31 * result + fieldPool.hashCode();
            result = 31 * result + methodPool.hashCode();
            result = 31 * result + instrumentedMethods.hashCode();
            return result;
        }

        /**
         * Creates the instrumented type.
         *
         * @param instrumentationContext The instrumentation context to use.
         * @return A byte array that represents the instrumented type.
         */
        protected abstract byte[] create(Instrumentation.Context.ExtractableView instrumentationContext);

        /**
         * A type writer that inlines the created type into an existing class file.
         *
         * @param <U> The best known loaded type for the dynamically created type.
         */
        public static class ForInlining<U> extends Default<U> {

            /**
             * Indicates that a class does not define an explicit super class.
             */
            private static final TypeDescription NO_SUPER_CLASS = null;

            /**
             * Indicates that a method should be retained.
             */
            private static final MethodDescription RETAIN_METHOD = null;

            /**
             * Indicates that a method should be ignored.
             */
            private static final MethodVisitor IGNORE_METHOD = null;

            /**
             * Indicates that an annotation should be ignored.
             */
            private static final AnnotationVisitor IGNORE_ANNOTATION = null;

            /**
             * The class file locator to use.
             */
            private final ClassFileLocator classFileLocator;

            /**
             * The target type that is to be redefined via inlining.
             */
            private final TypeDescription targetType;

            /**
             * The method rebase resolver to use.
             */
            private final MethodRebaseResolver methodRebaseResolver;

            /**
             * Creates a new type writer for inling a type into an existing type description.
             *
             * @param instrumentedType            The instrumented type that is to be written.
             * @param loadedTypeInitializer       The loaded type initializer of the instrumented type.
             * @param typeInitializer             The type initializer of the instrumented type.
             * @param explicitAuxiliaryTypes      A list of explicit auxiliary types that are to be added to the created dynamic type.
             * @param classFileVersion            The class file version of the written type.
             * @param auxiliaryTypeNamingStrategy A naming strategy that is used for naming auxiliary types.
             * @param classVisitorWrapper         A class visitor wrapper to apply during instrumentation.
             * @param attributeAppender           The type attribute appender to apply.
             * @param fieldPool                   The field pool to be used for instrumenting fields.
             * @param methodPool                  The method pool to be used for instrumenting methods.
             * @param instrumentedMethods         A list of all instrumented methods.
             * @param classFileLocator            The class file locator to use.
             * @param targetType                  The target type that is to be redefined via inlining.
             * @param methodRebaseResolver        The method rebase resolver to use.
             */
            protected ForInlining(TypeDescription instrumentedType,
                                  LoadedTypeInitializer loadedTypeInitializer,
                                  InstrumentedType.TypeInitializer typeInitializer,
                                  List<DynamicType> explicitAuxiliaryTypes,
                                  ClassFileVersion classFileVersion,
                                  AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                                  ClassVisitorWrapper classVisitorWrapper,
                                  TypeAttributeAppender attributeAppender,
                                  FieldPool fieldPool,
                                  MethodPool methodPool,
                                  MethodList instrumentedMethods,
                                  ClassFileLocator classFileLocator,
                                  TypeDescription targetType,
                                  MethodRebaseResolver methodRebaseResolver) {
                super(instrumentedType,
                        loadedTypeInitializer,
                        typeInitializer,
                        explicitAuxiliaryTypes,
                        classFileVersion,
                        auxiliaryTypeNamingStrategy,
                        classVisitorWrapper,
                        attributeAppender,
                        fieldPool,
                        methodPool,
                        instrumentedMethods);
                this.classFileLocator = classFileLocator;
                this.targetType = targetType;
                this.methodRebaseResolver = methodRebaseResolver;
            }

            @Override
            public byte[] create(Instrumentation.Context.ExtractableView instrumentationContext) {
                try {
                    ClassFileLocator.Resolution resolution = classFileLocator.locate(targetType.getName());
                    if (!resolution.isResolved()) {
                        throw new IllegalArgumentException("Cannot locate the class file for " + targetType + " using " + classFileLocator);
                    }
                    return doCreate(instrumentationContext, resolution.resolve());
                } catch (IOException e) {
                    throw new RuntimeException("The class file could not be written", e);
                }
            }

            /**
             * Performs the actual creation of a class file.
             *
             * @param instrumentationContext The instrumentation context to use for implementing the class file.
             * @param binaryRepresentation   The binary representation of the class file.
             * @return The byte array representing the created class.
             */
            private byte[] doCreate(Instrumentation.Context.ExtractableView instrumentationContext, byte[] binaryRepresentation) {
                ClassReader classReader = new ClassReader(binaryRepresentation);
                ClassWriter classWriter = new ClassWriter(classReader, ASM_MANUAL_FLAG);
                classReader.accept(writeTo(classVisitorWrapper.wrap(classWriter), instrumentationContext), ASM_MANUAL_FLAG);
                return classWriter.toByteArray();
            }

            /**
             * Creates a class visitor which weaves all changes and additions on the fly.
             *
             * @param classVisitor           The class visitor to which this entry is to be written to.
             * @param instrumentationContext The instrumentation context to use for implementing the class file.
             * @return A class visitor which is capable of applying the changes.
             */
            private ClassVisitor writeTo(ClassVisitor classVisitor, Instrumentation.Context.ExtractableView instrumentationContext) {
                String originalName = targetType.getInternalName();
                String targetName = instrumentedType.getInternalName();
                ClassVisitor targetClassVisitor = new RedefinitionClassVisitor(classVisitor, instrumentationContext);
                return originalName.equals(targetName)
                        ? targetClassVisitor
                        : new RemappingClassAdapter(targetClassVisitor, new SimpleRemapper(originalName, targetName));
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                if (!super.equals(other)) return false;
                ForInlining<?> that = (ForInlining<?>) other;
                return classFileLocator.equals(that.classFileLocator)
                        && targetType.equals(that.targetType)
                        && methodRebaseResolver.equals(that.methodRebaseResolver);
            }

            @Override
            public int hashCode() {
                int result = super.hashCode();
                result = 31 * result + classFileLocator.hashCode();
                result = 31 * result + targetType.hashCode();
                result = 31 * result + methodRebaseResolver.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "TypeWriter.Default.ForInlining{" +
                        "instrumentedType=" + instrumentedType +
                        ", loadedTypeInitializer=" + loadedTypeInitializer +
                        ", typeInitializer=" + typeInitializer +
                        ", explicitAuxiliaryTypes=" + explicitAuxiliaryTypes +
                        ", classFileVersion=" + classFileVersion +
                        ", auxiliaryTypeNamingStrategy=" + auxiliaryTypeNamingStrategy +
                        ", classVisitorWrapper=" + classVisitorWrapper +
                        ", attributeAppender=" + attributeAppender +
                        ", fieldPool=" + fieldPool +
                        ", methodPool=" + methodPool +
                        ", instrumentedMethods=" + instrumentedMethods +
                        ", classFileLocator=" + classFileLocator +
                        ", targetType=" + targetType +
                        ", methodRebaseResolver=" + methodRebaseResolver +
                        '}';
            }

            /**
             * A class visitor which is capable of applying a redefinition of an existing class file.
             */
            protected class RedefinitionClassVisitor extends ClassVisitor {

                /**
                 * The instrumentation context for this class creation.
                 */
                private final Instrumentation.Context.ExtractableView instrumentationContext;

                /**
                 * A mutable map of all declared fields of the instrumented type by their names.
                 */
                private final Map<String, FieldDescription> declaredFields;

                /**
                 * A mutable map of all declarable methods of the instrumented type by their unique signatures.
                 */
                private final Map<String, MethodDescription> declarableMethods;

                /**
                 * A mutable reference for code that is to be injected into the actual type initializer, if any.
                 * Usually, this represents an invocation of the actual type initializer that is found in the class
                 * file which is relocated into a static method.
                 */
                private Instrumentation.Context.ExtractableView.InjectedCode injectedCode;

                /**
                 * Creates a class visitor which is capable of redefining an existent class on the fly.
                 *
                 * @param classVisitor           The underlying class visitor to which writes are delegated.
                 * @param instrumentationContext The instrumentation context to use for implementing the class file.
                 */
                protected RedefinitionClassVisitor(ClassVisitor classVisitor, Instrumentation.Context.ExtractableView instrumentationContext) {
                    super(ASM_API_VERSION, classVisitor);
                    this.instrumentationContext = instrumentationContext;
                    List<? extends FieldDescription> fieldDescriptions = instrumentedType.getDeclaredFields();
                    declaredFields = new HashMap<String, FieldDescription>(fieldDescriptions.size());
                    for (FieldDescription fieldDescription : fieldDescriptions) {
                        declaredFields.put(fieldDescription.getInternalName(), fieldDescription);
                    }
                    declarableMethods = new HashMap<String, MethodDescription>(instrumentedMethods.size());
                    for (MethodDescription methodDescription : instrumentedMethods) {
                        declarableMethods.put(methodDescription.getUniqueSignature(), methodDescription);
                    }
                    injectedCode = Instrumentation.Context.ExtractableView.InjectedCode.None.INSTANCE;
                }

                @Override
                public void visit(int classFileVersionNumber,
                                  int modifiers,
                                  String internalName,
                                  String genericSignature,
                                  String superTypeInternalName,
                                  String[] interfaceTypeInternalName) {
                    ClassFileVersion originalClassFileVersion = new ClassFileVersion(classFileVersionNumber);
                    super.visit((classFileVersion.compareTo(originalClassFileVersion) > 0
                                    ? classFileVersion
                                    : originalClassFileVersion).getVersionNumber(),
                            instrumentedType.getActualModifiers((modifiers & Opcodes.ACC_SUPER) != 0),
                            instrumentedType.getInternalName(),
                            instrumentedType.getGenericSignature(),
                            (instrumentedType.getSupertype() == NO_SUPER_CLASS ?
                                    TypeDescription.OBJECT :
                                    instrumentedType.getSupertype()).getInternalName(),
                            instrumentedType.getInterfaces().toInternalNames());
                    attributeAppender.apply(this, instrumentedType);
                }

                @Override
                public FieldVisitor visitField(int modifiers,
                                               String internalName,
                                               String descriptor,
                                               String genericSignature,
                                               Object defaultValue) {
                    declaredFields.remove(internalName); // Ignore in favor of the class file definition.
                    return super.visitField(modifiers, internalName, descriptor, genericSignature, defaultValue);
                }

                @Override
                public MethodVisitor visitMethod(int modifiers,
                                                 String internalName,
                                                 String descriptor,
                                                 String genericSignature,
                                                 String[] exceptionTypeInternalName) {
                    if (internalName.equals(MethodDescription.TYPE_INITIALIZER_INTERNAL_NAME)) {
                        TypeInitializerInjection injectedCode = new TypeInitializerInjection();
                        this.injectedCode = injectedCode;
                        return super.visitMethod(injectedCode.getInjectorProxyMethod().getModifiers(),
                                injectedCode.getInjectorProxyMethod().getInternalName(),
                                injectedCode.getInjectorProxyMethod().getDescriptor(),
                                injectedCode.getInjectorProxyMethod().getGenericSignature(),
                                injectedCode.getInjectorProxyMethod().getExceptionTypes().toInternalNames());
                    }
                    MethodDescription methodDescription = declarableMethods.remove(internalName + descriptor);
                    return methodDescription == RETAIN_METHOD
                            ? super.visitMethod(modifiers, internalName, descriptor, genericSignature, exceptionTypeInternalName)
                            : redefine(methodDescription, (modifiers & Opcodes.ACC_ABSTRACT) != 0);
                }

                /**
                 * Redefines a given method if this is required by looking up a potential implementation from the
                 * {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool}.
                 *
                 * @param methodDescription The method being considered for redefinition.
                 * @param abstractOrigin    {@code true} if the original method is abstract, i.e. there is no implementation
                 *                          to preserve.
                 * @return A method visitor which is capable of consuming the original method.
                 */
                protected MethodVisitor redefine(MethodDescription methodDescription, boolean abstractOrigin) {
                    TypeWriter.MethodPool.Entry entry = methodPool.target(methodDescription);
                    if (!entry.getSort().isDefined()) {
                        return super.visitMethod(methodDescription.getModifiers(),
                                methodDescription.getInternalName(),
                                methodDescription.getDescriptor(),
                                methodDescription.getGenericSignature(),
                                methodDescription.getExceptionTypes().toInternalNames());
                    }
                    MethodVisitor methodVisitor = super.visitMethod(
                            methodDescription.getAdjustedModifiers(entry.getSort().isImplemented()),
                            methodDescription.getInternalName(),
                            methodDescription.getDescriptor(),
                            methodDescription.getGenericSignature(),
                            methodDescription.getExceptionTypes().toInternalNames());
                    return abstractOrigin
                            ? new AttributeObtainingMethodVisitor(methodVisitor, entry, methodDescription)
                            : new CodePreservingMethodVisitor(methodVisitor, entry, methodDescription);
                }

                @Override
                public void visitEnd() {
                    for (FieldDescription fieldDescription : declaredFields.values()) {
                        fieldPool.target(fieldDescription).apply(cv, fieldDescription);
                    }
                    for (MethodDescription methodDescription : declarableMethods.values()) {
                        methodPool.target(methodDescription).apply(cv, instrumentationContext, methodDescription);
                    }
                    instrumentationContext.drain(cv, methodPool, injectedCode);
                    super.visitEnd();
                }

                @Override
                public String toString() {
                    return "TypeWriter.Default.ForInlining.RedefinitionClassVisitor{" +
                            "typeWriter=" + TypeWriter.Default.ForInlining.this +
                            ", instrumentationContext=" + instrumentationContext +
                            ", declaredFields=" + declaredFields +
                            ", declarableMethods=" + declarableMethods +
                            ", injectedCode=" + injectedCode +
                            '}';
                }

                /**
                 * A method visitor that preserves the code of a method in the class file by copying it into a rebased
                 * method while copying all attributes and annotations to the actual method.
                 */
                protected class CodePreservingMethodVisitor extends MethodVisitor {

                    /**
                     * The method visitor of the actual method.
                     */
                    private final MethodVisitor actualMethodVisitor;

                    /**
                     * The method pool entry to apply.
                     */
                    private final MethodPool.Entry entry;

                    /**
                     * A description of the actual method.
                     */
                    private final MethodDescription methodDescription;

                    /**
                     * The resolution of a potential rebased method.
                     */
                    private final MethodRebaseResolver.Resolution resolution;

                    /**
                     * Creates a new code preserving method visitor.
                     *
                     * @param actualMethodVisitor The method visitor of the actual method.
                     * @param entry               The method pool entry to apply.
                     * @param methodDescription   A description of the actual method.
                     */
                    protected CodePreservingMethodVisitor(MethodVisitor actualMethodVisitor,
                                                          MethodPool.Entry entry,
                                                          MethodDescription methodDescription) {
                        super(ASM_API_VERSION, actualMethodVisitor);
                        this.actualMethodVisitor = actualMethodVisitor;
                        this.entry = entry;
                        this.methodDescription = methodDescription;
                        this.resolution = methodRebaseResolver.resolve(methodDescription);
                        entry.applyHead(actualMethodVisitor, methodDescription);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        return IGNORE_ANNOTATION; // Annotation types can never be rebased.
                    }

                    @Override
                    public void visitCode() {
                        entry.applyBody(actualMethodVisitor, instrumentationContext, methodDescription);
                        actualMethodVisitor.visitEnd();
                        mv = resolution.isRebased()
                                ? cv.visitMethod(resolution.getResolvedMethod().getModifiers(),
                                resolution.getResolvedMethod().getInternalName(),
                                resolution.getResolvedMethod().getDescriptor(),
                                resolution.getResolvedMethod().getGenericSignature(),
                                resolution.getResolvedMethod().getExceptionTypes().toInternalNames())
                                : IGNORE_METHOD;
                        super.visitCode();
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack, Math.max(maxLocals, resolution.getResolvedMethod().getStackSize()));
                    }

                    @Override
                    public String toString() {
                        return "TypeWriter.Default.ForInlining.RedefinitionClassVisitor.CodePreservingMethodVisitor{" +
                                "classVisitor=" + TypeWriter.Default.ForInlining.RedefinitionClassVisitor.this +
                                ", actualMethodVisitor=" + actualMethodVisitor +
                                ", entry=" + entry +
                                ", methodDescription=" + methodDescription +
                                ", resolution=" + resolution +
                                '}';
                    }
                }

                /**
                 * A method visitor that obtains all attributes and annotations of a method that is found in the
                 * class file but discards all code.
                 */
                protected class AttributeObtainingMethodVisitor extends MethodVisitor {

                    /**
                     * The method visitor to which the actual method is to be written to.
                     */
                    private final MethodVisitor actualMethodVisitor;

                    /**
                     * The method pool entry to apply.
                     */
                    private final MethodPool.Entry entry;

                    /**
                     * A description of the method that is currently written.
                     */
                    private final MethodDescription methodDescription;

                    /**
                     * Creates a new attribute obtaining method visitor.
                     *
                     * @param actualMethodVisitor The method visitor of the actual method.
                     * @param entry               The method pool entry to apply.
                     * @param methodDescription   A description of the actual method.
                     */
                    protected AttributeObtainingMethodVisitor(MethodVisitor actualMethodVisitor,
                                                              MethodPool.Entry entry,
                                                              MethodDescription methodDescription) {
                        super(ASM_API_VERSION, actualMethodVisitor);
                        this.actualMethodVisitor = actualMethodVisitor;
                        this.entry = entry;
                        this.methodDescription = methodDescription;
                        entry.applyHead(actualMethodVisitor, methodDescription);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        return IGNORE_ANNOTATION;
                    }

                    @Override
                    public void visitCode() {
                        mv = IGNORE_METHOD;
                    }

                    @Override
                    public void visitEnd() {
                        entry.applyBody(actualMethodVisitor, instrumentationContext, methodDescription);
                        actualMethodVisitor.visitEnd();
                    }

                    @Override
                    public String toString() {
                        return "TypeWriter.Default.ForInlining.RedefinitionClassVisitor.AttributeObtainingMethodVisitor{" +
                                "classVisitor=" + TypeWriter.Default.ForInlining.RedefinitionClassVisitor.this +
                                ", actualMethodVisitor=" + actualMethodVisitor +
                                ", entry=" + entry +
                                ", methodDescription=" + methodDescription +
                                '}';
                    }
                }

                /**
                 * A code injection for the type initializer that invokes a method representing the original type initializer
                 * which is copied to a static method.
                 */
                protected class TypeInitializerInjection implements Instrumentation.Context.ExtractableView.InjectedCode {

                    /**
                     * The modifiers for the method that consumes the original type initializer.
                     */
                    private static final int TYPE_INITIALIZER_PROXY_MODIFIERS = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

                    /**
                     * A prefix for the name of the method that represents the original type initializer.
                     */
                    private static final String TYPE_INITIALIZER_PROXY_PREFIX = "originalTypeInitializer";

                    /**
                     * The method to which the original type initializer code is to be written to.
                     */
                    private final MethodDescription injectorProxyMethod;

                    /**
                     * Creates a new type initializer injection.
                     */
                    private TypeInitializerInjection() {
                        injectorProxyMethod = new MethodDescription.Latent(
                                String.format("%s$%s", TYPE_INITIALIZER_PROXY_PREFIX, RandomString.make()),
                                instrumentedType,
                                TypeDescription.VOID,
                                new TypeList.Empty(),
                                TYPE_INITIALIZER_PROXY_MODIFIERS,
                                Collections.<TypeDescription>emptyList());
                    }

                    @Override
                    public StackManipulation getStackManipulation() {
                        return MethodInvocation.invoke(injectorProxyMethod);
                    }

                    @Override
                    public boolean isDefined() {
                        return true;
                    }

                    /**
                     * Returns the proxy method to which the original type initializer code is written to.
                     *
                     * @return A method description of this proxy method.
                     */
                    public MethodDescription getInjectorProxyMethod() {
                        return injectorProxyMethod;
                    }

                    @Override
                    public String toString() {
                        return "TypeWriter.Default.ForInlining.RedefinitionClassVisitor.TypeInitializerInjection{" +
                                "classVisitor=" + TypeWriter.Default.ForInlining.RedefinitionClassVisitor.this +
                                ", injectorProxyMethod=" + injectorProxyMethod +
                                '}';
                    }
                }
            }
        }

        /**
         * A type writer that creates a class file that is not based upon another, existing class.
         *
         * @param <U> The best known loaded type for the dynamically created type.
         */
        public static class ForCreation<U> extends Default<U> {

            /**
             * Creates a new type writer for creating a new type.
             *
             * @param instrumentedType            The instrumented type that is to be written.
             * @param loadedTypeInitializer       The loaded type initializer of the instrumented type.
             * @param typeInitializer             The type initializer of the instrumented type.
             * @param explicitAuxiliaryTypes      A list of explicit auxiliary types that are to be added to the created dynamic type.
             * @param classFileVersion            The class file version of the written type.
             * @param auxiliaryTypeNamingStrategy A naming strategy that is used for naming auxiliary types.
             * @param classVisitorWrapper         A class visitor wrapper to apply during instrumentation.
             * @param attributeAppender           The type attribute appender to apply.
             * @param fieldPool                   The field pool to be used for instrumenting fields.
             * @param methodPool                  The method pool to be used for instrumenting methods.
             * @param instrumentedMethods         A list of all instrumented methods.
             */
            protected ForCreation(TypeDescription instrumentedType,
                                  LoadedTypeInitializer loadedTypeInitializer,
                                  InstrumentedType.TypeInitializer typeInitializer,
                                  List<DynamicType> explicitAuxiliaryTypes,
                                  ClassFileVersion classFileVersion,
                                  AuxiliaryType.NamingStrategy auxiliaryTypeNamingStrategy,
                                  ClassVisitorWrapper classVisitorWrapper,
                                  TypeAttributeAppender attributeAppender,
                                  FieldPool fieldPool,
                                  MethodPool methodPool,
                                  MethodList instrumentedMethods) {
                super(instrumentedType,
                        loadedTypeInitializer,
                        typeInitializer,
                        explicitAuxiliaryTypes,
                        classFileVersion,
                        auxiliaryTypeNamingStrategy,
                        classVisitorWrapper,
                        attributeAppender,
                        fieldPool,
                        methodPool,
                        instrumentedMethods);
            }

            @Override
            public byte[] create(Instrumentation.Context.ExtractableView instrumentationContext) {
                ClassWriter classWriter = new ClassWriter(ASM_MANUAL_FLAG);
                ClassVisitor classVisitor = classVisitorWrapper.wrap(classWriter);
                classVisitor.visit(classFileVersion.getVersionNumber(),
                        instrumentedType.getActualModifiers(!instrumentedType.isInterface()),
                        instrumentedType.getInternalName(),
                        instrumentedType.getGenericSignature(),
                        (instrumentedType.getSupertype() == null
                                ? TypeDescription.OBJECT
                                : instrumentedType.getSupertype()).getInternalName(),
                        instrumentedType.getInterfaces().toInternalNames());
                attributeAppender.apply(classVisitor, instrumentedType);
                for (FieldDescription fieldDescription : instrumentedType.getDeclaredFields()) {
                    fieldPool.target(fieldDescription).apply(classVisitor, fieldDescription);
                }
                for (MethodDescription methodDescription : instrumentedMethods) {
                    methodPool.target(methodDescription).apply(classVisitor, instrumentationContext, methodDescription);
                }
                instrumentationContext.drain(classVisitor, methodPool, Instrumentation.Context.ExtractableView.InjectedCode.None.INSTANCE);
                classVisitor.visitEnd();
                return classWriter.toByteArray();
            }

            @Override
            public String toString() {
                return "TypeWriter.Default.ForCreation{" +
                        "instrumentedType=" + instrumentedType +
                        ", loadedTypeInitializer=" + loadedTypeInitializer +
                        ", typeInitializer=" + typeInitializer +
                        ", explicitAuxiliaryTypes=" + explicitAuxiliaryTypes +
                        ", classFileVersion=" + classFileVersion +
                        ", auxiliaryTypeNamingStrategy=" + auxiliaryTypeNamingStrategy +
                        ", classVisitorWrapper=" + classVisitorWrapper +
                        ", attributeAppender=" + attributeAppender +
                        ", fieldPool=" + fieldPool +
                        ", methodPool=" + methodPool +
                        ", instrumentedMethods=" + instrumentedMethods +
                        "}";
            }
        }
    }
}
