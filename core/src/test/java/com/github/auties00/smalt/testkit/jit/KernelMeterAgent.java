package com.github.auties00.smalt.testkit.jit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * Instruments the equivalence tests a {@link KernelGuardChild} runs: every call whose owner is a kernel class, or a
 * Smalt supertype of one, goes through {@link KernelMeter}.
 *
 * <p>The agent's argument is a file of lines {@code type <binary name>} (an owner whose calls are instrumented),
 * {@code test <binary name>} (a test class, instrumented with its nested classes) and {@code setup <name(descriptor)>}
 * (a method marked {@code KernelSetup}, whose calls are left alone, as are static factories returning a kernel type). Only the test classes change; the
 * kernels themselves are loaded as compiled, so the code the JIT compilers see is the code that ships. An instrumented
 * call stores its arguments in new locals and becomes
 * {@snippet :
 * KernelMeter.begin(receiver, new Object[] {arguments...}, site);
 * do {
 *     KernelMeter.mark();
 *     result = receiver.kernel(arguments...);
 * } while (KernelMeter.end());
 * }
 */
public final class KernelMeterAgent {
    private static final ClassDesc METER = ClassDesc.of(KernelMeter.class.getName());
    private static final MethodTypeDesc BEGIN = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
            ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_int);
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc BOOLEAN = MethodTypeDesc.of(ConstantDescs.CD_boolean);

    private KernelMeterAgent() {
        throw new AssertionError();
    }

    /**
     * Installs the transformer.
     *
     * @param arguments the path of the configuration file
     * @param instrumentation the instrumentation
     */
    public static void premain(String arguments, Instrumentation instrumentation) {
        var types = new HashSet<String>();
        var tests = new HashSet<String>();
        var setup = new HashSet<String>();
        try {
            for (var line : Files.readAllLines(Path.of(arguments))) {
                var space = line.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                var internal = line.substring(space + 1).strip().replace('.', '/');
                switch (line.substring(0, space)) {
                    case "type" -> types.add(internal);
                    case "test" -> tests.add(internal);
                    case "setup" -> setup.add(line.substring(space + 1).strip());
                    default -> {
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        instrumentation.addTransformer(new Transformer(Set.copyOf(types), Set.copyOf(tests), Set.copyOf(setup)));
    }

    private record Transformer(Set<String> types, Set<String> tests, Set<String> setup)
            implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                                byte[] bytes) {
            if (name == null || loader == null || !test(name)) {
                return null;
            }
            try {
                var resolver = ClassHierarchyResolver.ofResourceParsing(loader)
                        .orElse(ClassHierarchyResolver.defaultResolver());
                var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
                var model = classFile.parse(bytes);
                var binary = name.replace('/', '.');
                return classFile.transformClass(model, ClassTransform.transformingMethodBodies(
                        CodeTransform.ofStateful(() -> new Meter(binary, types, setup))));
            } catch (RuntimeException | Error failure) {
                failure.printStackTrace();
                return null;
            }
        }

        private boolean test(String name) {
            var dollar = name.indexOf('$');
            return tests.contains(dollar < 0 ? name : name.substring(0, dollar));
        }
    }

    private static final class Meter implements CodeTransform {
        private final String caller;
        private final Set<String> types;
        private final Set<String> setup;
        private int line;

        private Meter(String caller, Set<String> types, Set<String> setup) {
            this.caller = caller;
            this.types = types;
            this.setup = setup;
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof LineNumber number) {
                line = number.line();
            }
            if (!(element instanceof InvokeInstruction invoke) || invoke.opcode() == Opcode.INVOKESPECIAL
                    || !types.contains(invoke.owner().asInternalName()) || factory(invoke)
                    || setup.contains(invoke.name().stringValue() + invoke.typeSymbol().descriptorString())) {
                builder.with(element);
                return;
            }
            var type = invoke.typeSymbol();
            var isStatic = invoke.opcode() == Opcode.INVOKESTATIC;
            var site = KernelMeter.register(new KernelMeter.Site(simple(caller), line,
                    invoke.owner().asInternalName().replace('/', '.'), invoke.name().stringValue(),
                    type.descriptorString(), isStatic));
            var count = type.parameterCount();
            var slots = new int[count];
            for (var i = count - 1; i >= 0; i--) {
                var kind = TypeKind.from(type.parameterType(i));
                slots[i] = builder.allocateLocal(kind);
                builder.storeLocal(kind, slots[i]);
            }
            var receiver = -1;
            if (!isStatic) {
                receiver = builder.allocateLocal(TypeKind.REFERENCE);
                builder.astore(receiver);
                builder.aload(receiver);
            } else {
                builder.aconst_null();
            }
            builder.loadConstant(count);
            builder.anewarray(ConstantDescs.CD_Object);
            for (var i = 0; i < count; i++) {
                builder.dup();
                builder.loadConstant(i);
                var parameter = type.parameterType(i);
                builder.loadLocal(TypeKind.from(parameter), slots[i]);
                box(builder, parameter);
                builder.aastore();
            }
            builder.loadConstant(site);
            builder.invokestatic(METER, "begin", BEGIN);
            var loop = builder.newBoundLabel();
            if (!isStatic) {
                builder.aload(receiver);
            }
            for (var i = 0; i < count; i++) {
                builder.loadLocal(TypeKind.from(type.parameterType(i)), slots[i]);
            }
            builder.invokestatic(METER, "mark", VOID);
            builder.with(invoke);
            var returned = TypeKind.from(type.returnType());
            var result = -1;
            if (returned != TypeKind.VOID) {
                result = builder.allocateLocal(returned);
                builder.storeLocal(returned, result);
            }
            builder.invokestatic(METER, "end", BOOLEAN);
            builder.ifne(loop);
            if (returned != TypeKind.VOID) {
                builder.loadLocal(returned, result);
            }
        }

        /** Returns whether a call is a static factory of a kernel type, which allocates the kernel by design. */
        private boolean factory(InvokeInstruction invoke) {
            var returned = invoke.typeSymbol().returnType().descriptorString();
            return invoke.opcode() == Opcode.INVOKESTATIC && returned.startsWith("L")
                    && types.contains(returned.substring(1, returned.length() - 1));
        }

        private static void box(CodeBuilder builder, ClassDesc type) {
            if (!type.isPrimitive()) {
                return;
            }
            var wrapper = switch (type.descriptorString()) {
                case "Z" -> ConstantDescs.CD_Boolean;
                case "B" -> ConstantDescs.CD_Byte;
                case "C" -> ConstantDescs.CD_Character;
                case "S" -> ConstantDescs.CD_Short;
                case "I" -> ConstantDescs.CD_Integer;
                case "J" -> ConstantDescs.CD_Long;
                case "F" -> ConstantDescs.CD_Float;
                default -> ConstantDescs.CD_Double;
            };
            builder.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, type));
        }

        private static String simple(String binary) {
            return binary.substring(binary.lastIndexOf('.') + 1);
        }
    }
}
