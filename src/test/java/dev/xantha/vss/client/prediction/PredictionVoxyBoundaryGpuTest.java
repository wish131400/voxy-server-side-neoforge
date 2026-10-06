package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionVoxyBoundaryGpuTest {
    private long window;
    private int program;

    @BeforeEach
    void openContext() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(16, 16, "Voxy boundary shader", 0, 0);
        assertNotEquals(0L, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        linkProbe();
    }

    private void linkProbe() {
        String vertex = PredictionVoxyBoundaryBridge.patch("voxy:lod/gl46/quads3.vert", """
                #version 460 core
                layout(location = 0) out flat uvec4 interData;
                uniform vec3 cameraSubPos;
                uniform vec3 WorldPosition;
                uniform uint Face;
                struct QuadData { vec3 basePoint; vec2 quadSizeAddin; float lodScale; uint axis; };
                uint quadData[1] = uint[1](0u);
                vec3 swizzelDataAxis(uint axis, vec3 data) { return data; }
                uint extractFace(uint data) { return Face; }
                void setupQuad(out QuadData quad, uint data, uvec2 pos, bool attributes) {
                    quad.basePoint = WorldPosition;
                    quad.quadSizeAddin = vec2(1.0);
                    quad.lodScale = 1.0;
                    quad.axis = 0u;
                }
                void main() {
                    QuadData quad;
                    uvec2 pos = uvec2(0);
                    setupQuad(quad, quadData[uint(gl_VertexID) >> 2], pos, (gl_VertexID & 3) == 1);
                    interData = uvec4(0u);
                    gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0,
                            gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);
                }
                """);
        String fragment = PredictionVoxyBoundaryBridge.patch("voxy:lod/gl46/quads.frag", """
                #version 460 core
                layout(location = 0) in flat uvec4 interData;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """);
        assertFalse(vertex.contains("VssStrictNearFirst"));
        assertFalse(vertex.contains("VssStrictVisibleBounds"));
        assertFalse(fragment.contains("VssStrictNearFirst"));
        assertFalse(fragment.contains("VssStrictVisibleBounds"));
        int vs = compile(GL_VERTEX_SHADER, vertex);
        int fs = compile(GL_FRAGMENT_SHADER, fragment);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        assertEquals(-1, glGetUniformLocation(program, "VssStrictNearFirst"));
        assertEquals(-1, glGetUniformLocation(program, "VssStrictVisibleBounds"));
        assertNotEquals(-1, glGetUniformLocation(program, "VssBoundaryEnabled"));
        assertNotEquals(-1, glGetUniformLocation(program, "VssBoundaryCoverage"));
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    @Test void onlyReplacementBoundaryWallsCanBeHidden() {
        int vao = glGenVertexArrays();
        try {
            glBindVertexArray(vao);
            glUseProgram(program);
            glViewport(0, 0, 16, 16);
            glUniform1f(glGetUniformLocation(program, "VssBoundaryRadius"), 1024);
            glUniform4ui(glGetUniformLocation(program, "VssBoundaryCoverage"), -1, -1, -1, -1);
            assertPixel(8192, 0, false, 255);
            assertPixel(8192, 0, true, 255);
            assertPixel(8192, 5, true, 255);
            assertPixel(1024, 0, true, 255);
            assertPixel(1024, 5, false, 255);
            assertPixel(1024, 5, true, 0);
            glUniform4ui(glGetUniformLocation(program, "VssBoundaryCoverage"), 0, 0, 0, 0);
            assertPixel(1024, 5, true, 255);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glUseProgram(0);
            glDeleteVertexArrays(vao);
        }
    }

    private void assertPixel(float distance, int face, boolean enabled, int expected) {
        glUniform3f(glGetUniformLocation(program, "WorldPosition"), distance, 0, 0);
        glUniform1ui(glGetUniformLocation(program, "Face"), face);
        glUniform1i(glGetUniformLocation(program, "VssBoundaryEnabled"), enabled ? 1 : 0);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var pixel = org.lwjgl.BufferUtils.createByteBuffer(4);
        glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        for (int channel = 0; channel < 3; channel++) assertEquals(expected, pixel.get(channel) & 255,
                "distance=" + distance + ",face=" + face + ",enabled=" + enabled);
    }

    @AfterEach
    void closeContext() {
        if (program != 0) glDeleteProgram(program);
        if (window != 0) glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }
}
