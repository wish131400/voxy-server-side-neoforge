package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.List;

/** Minimal render-thread GLSL program used by prediction depth conversion. */
final class GlProgram implements AutoCloseable {
    private static final int LOG_LENGTH = 32_768;
    private final int id;

    private GlProgram(int id) {
        this.id = id;
    }

    static GlProgram link(String name, String vertexSource, String fragmentSource) {
        int vertex = compile(name, 0x8B31, vertexSource);
        int fragment = compile(name, 0x8B30, fragmentSource);
        int program = GlStateManager.glCreateProgram();
        GlStateManager.glAttachShader(program, vertex);
        GlStateManager.glAttachShader(program, fragment);
        GlStateManager.glLinkProgram(program);
        GlStateManager.glDeleteShader(vertex);
        GlStateManager.glDeleteShader(fragment);
        if (GlStateManager.glGetProgrami(program, 0x8B82) == 0) {
            String log = GlStateManager.glGetProgramInfoLog(program, LOG_LENGTH);
            GlStateManager.glDeleteProgram(program);
            throw new IllegalStateException("VSS shader " + name + " failed to link: " + log);
        }
        return new GlProgram(program);
    }

    private static int compile(String name, int type, String source) {
        int shader = GlStateManager.glCreateShader(type);
        GlStateManager.glShaderSource(shader, List.of(source));
        GlStateManager.glCompileShader(shader);
        if (GlStateManager.glGetShaderi(shader, 0x8B81) == 0) {
            String log = GlStateManager.glGetShaderInfoLog(shader, LOG_LENGTH);
            GlStateManager.glDeleteShader(shader);
            throw new IllegalStateException("VSS shader " + name + " failed to compile: " + log);
        }
        return shader;
    }

    int uniform(String name) {
        return GlStateManager._glGetUniformLocation(id, name);
    }

    void use() {
        GlStateManager._glUseProgram(id);
    }

    static void unuse() {
        GlStateManager._glUseProgram(0);
    }

    @Override
    public void close() {
        GlStateManager.glDeleteProgram(id);
    }
}
