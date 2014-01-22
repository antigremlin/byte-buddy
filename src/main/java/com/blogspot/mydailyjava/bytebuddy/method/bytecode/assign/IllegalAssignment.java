package com.blogspot.mydailyjava.bytebuddy.method.bytecode.assign;

import org.objectweb.asm.MethodVisitor;

public enum IllegalAssignment implements Assignment {
    INSTANCE;

    @Override
    public boolean isAssignable() {
        return false;
    }

    @Override
    public Size apply(MethodVisitor methodVisitor) {
        throw new IllegalStateException("It is not possible to apply an illegal assignment as byte code");
    }
}