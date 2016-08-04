package com.jtransc.llvm2jvm;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings({"WeakerAccess", "unused"})
public class LlvmRuntime {
    static public ByteBuffer MEM = ByteBuffer.allocateDirect(0x100000).order(ByteOrder.LITTLE_ENDIAN);
    static public int SP = MEM.limit() - 0x10;

    static public int li8(int offset) {
        return MEM.get(offset) & 0xFF;
    }

    static public void si8(int offset, int value) {
        MEM.put(offset, (byte) value);
    }

    static public int li32(int offset) {
        //System.out.println("mem[" + offset + "]");
        return MEM.getInt(offset);
    }

    static public long li64(int offset) {
        //System.out.println("mem[" + offset + "]");
        return MEM.getLong(offset);
    }

    static public void si32(int offset, int value) {
        //System.out.println("mem[" + offset + "] = " + value);
        MEM.putInt(offset, value);
    }

    static public int alloca(int size) {
        SP -= size;
        //System.out.println("alloca: " + size);
        return SP;
    }

    static public int getelementptr(int offset, int index, int size) {
        return offset + index * size;
    }

    static public void initStaticData(String data) {
        for (int n = 0; n < data.length(); n++) {
            byte v = (byte) data.charAt(n);
            MEM.put(n, v);
            //System.out.println("PUT: " + n + ": " + v);
        }
    }

    static public int puts(int ptr) {
        System.out.print(getStringz(ptr));
        return 0;
    }

    // @TODO: This shouldn't exist! but should fix function calling with other signatures
    static public int puts(int... ptrs) {
        System.out.print(getStringz(ptrs[0]));
        return 0;
    }

    static public String getStringz(int ptr) {
        String out = "";
        while (true) {
            char c = (char) li8(ptr);
            if (c == 0) break;
            out += c;
            ptr++;
        }
        return out;
    }

    static public int printf(int format, int... ptr) {
        String fmt = getStringz(format);
        int argpos = 0;
        int n = 0;
        while (n < fmt.length()) {
            char c =  fmt.charAt(n++);
            switch (c) {
                case '%':
                    int arg = ptr[argpos++];
                    char c2 =  fmt.charAt(n++);
                    switch (c2) {
                        case 'd':
                            System.out.print(arg);
                            break;
                    }
                    break;
                default:
                    System.out.print(c);
                    break;
            }
        }
        return 0;
    }

    static public void mainBootstrap(Class<?> clazz) {
        try {
            Method mainMethod = clazz.getDeclaredMethod("main");
            Object result = mainMethod.invoke(null);
            System.exit((int)result);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    static public boolean slt(int a, int b) { return (a < b); }
    static public boolean sgt(int a, int b) { return (a > b); }
    static public boolean ult(int a, int b) { return Integer.compareUnsigned(a, b) < 0; }

    static public boolean slt(long a, long b) { return (a < b); }
    static public boolean sgt(long a, long b) { return (a > b); }
    static public boolean ult(long a, long b) { return Long.compareUnsigned(a, b) < 0; }

    static public void llvm_memcpy_p0i8_p0i8_i64(int dst, int src, long len, int align, boolean isvolatile) {
        for (int n = 0; n < len; n++) {
            si8(dst + n, li8(src + n));
        }
        //System.out.println("llvm_memcpy_p0i8_p0i8_i64");
    }

    static public void llvm_va_start(int ptr) {
    }

    static public void llvm_va_end(int ptr) {
    }
}
