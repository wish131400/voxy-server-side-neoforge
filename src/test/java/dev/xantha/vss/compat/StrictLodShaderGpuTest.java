package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class StrictLodShaderGpuTest {
    @Test void cachedFarFragmentsKeepColorAndDepthRegardlessOfRequestFrontier() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(16, 16, "Strict LOD GPU regression", 0, 0);
        assertNotEquals(0L, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            int vs = compile(GL_VERTEX_SHADER, """
                    #version 330 core
                    void main() { gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0); }
                    """);
            String fragment = """
                    #version 330 core
                    uniform ivec3 baseSectionPos;
                    uniform vec3 vssStrictPosition;
                    out vec4 color;
                    void main() { color = vec4(1.0, 0.0, 0.0, 1.0); }
                    """;
            int fs = compile(GL_FRAGMENT_SHADER, dev.xantha.vss.client.prediction.PredictionFogBridge.patch("voxy:lod/gl46/quads.frag", fragment));
            int program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
            assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
            glUseProgram(program);
            int vao = glGenVertexArrays(); glBindVertexArray(vao); glViewport(0, 0, 16, 16);
            glEnable(GL_DEPTH_TEST);
            assertEquals(-1, glGetUniformLocation(program, "VssStrictEnabled"));
            for (int[] test : new int[][] {
                    {0,0,0,0,-1,0}, {0,0,0,0,0,1}, {0,0,16,0,0,0},
                    {0,0,16,0,1,1}, {-1,-1,31,31,0,0}, {-1,-1,31,31,1,1},
                    {64,0,0,0,127,0}, {64,0,0,0,128,1}}) {
                glUniform3i(glGetUniformLocation(program, "baseSectionPos"), test[0],0,test[1]);
                glUniform3f(glGetUniformLocation(program, "vssStrictPosition"), test[2],0,test[3]);
                glUniform3i(glGetUniformLocation(program, "VssStrictFrontier"), 0,0,test[4]);
                glClearColor(0,0,1,1); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                var pixel = BufferUtils.createByteBuffer(4);
                var depth = BufferUtils.createFloatBuffer(1);
                glReadPixels(8,8,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                glReadPixels(8,8,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                assertEquals(255, pixel.get(0) & 255, "missing inner rings must not hide cached far color");
                assertEquals(0.5f, depth.get(0), 0.001f, "cached far terrain must keep its depth");
            }
            assertEquals(GL_NO_ERROR, glGetError());
            glDeleteVertexArrays(vao); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }
    private static int compile(int type, String source) {
        int shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader)); return shader;
    }
}
