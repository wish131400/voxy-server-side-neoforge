package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
@EnabledIfSystemProperty(named="vss.voxyJar",matches=".+")
class StrictVoxyShaderFixtureGpuTest {
    @Test void realVoxyProgramsLinkWithoutARequestFrontierGate() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(16,16,"Voxy strict shader compatibility",0,0);
        assertNotEquals(0L,window);
        try (ZipFile jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            check(jar,null);
            String source=System.getProperty("vss.voxySource");
            if(source!=null) check(null,Path.of(source));
        } finally { glfwDestroyWindow(window);glfwTerminate(); }
    }
    private static void check(ZipFile jar,Path root) throws Exception {
        String vertex=dev.xantha.vss.client.prediction.PredictionFogBridge.patch("voxy:lod/gl46/quads3.vert",load(jar,root,"voxy:lod/gl46/quads3.vert"));
        String fragment=dev.xantha.vss.client.prediction.PredictionFogBridge.patch("voxy:lod/gl46/quads.frag",load(jar,root,"voxy:lod/gl46/quads.frag"));
        for(String mode:new String[]{"", "#define TRANSLUCENT\n", "#define PATCHED_SHADER\n"}) {
            String defines=mode+"#define UP_FACE_TINT 1.0\n#define DOWN_FACE_TINT 0.5\n#define Z_AXIS_FACE_TINT 0.8\n#define X_AXIS_FACE_TINT 0.6\n#define NO_SHADE_FACE_TINT 1.0\n";
            int vs=compile(GL_VERTEX_SHADER,vertex.replaceFirst("\n","\n"+defines));
            String fsSource=fragment;
            if(mode.contains("PATCHED_SHADER")) fsSource += "\nlayout(location=0) out vec4 vssProbeOutput;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { vssProbeOutput = p.sampledColour; }\n";
            int fs=compile(GL_FRAGMENT_SHADER,fsSource.replaceFirst("\n","\n"+defines));
            int p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);
            assertEquals(GL_TRUE,glGetProgrami(p,GL_LINK_STATUS),glGetProgramInfoLog(p));
            assertEquals(-1,glGetUniformLocation(p,"VssStrictEnabled"));
            assertEquals(-1,glGetUniformLocation(p,"VssStrictFrontier"));
            glDeleteProgram(p);glDeleteShader(vs);glDeleteShader(fs);
        }
    }
    private static String load(ZipFile jar,Path root,String id) throws Exception {
        String[] parts=id.split(":",2);
        String path="assets/"+parts[0]+"/shaders/"+parts[1];
        String source=jar!=null?new String(jar.getInputStream(jar.getEntry(path)).readAllBytes(),StandardCharsets.UTF_8):Files.readString(root.resolve(path));
        var matcher=Pattern.compile("#import <([^>]+)>").matcher(source);
        StringBuilder result=new StringBuilder();int last=0;
        while(matcher.find()) {result.append(source,last,matcher.start()).append(load(jar,root,matcher.group(1)));last=matcher.end();}
        return result.append(source.substring(last)).toString().replace("\r\n","\n");
    }
    private static int compile(int type,String source) {
        int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);
        assertEquals(GL_TRUE,glGetShaderi(s,GL_COMPILE_STATUS),glGetShaderInfoLog(s));return s;
    }
}
