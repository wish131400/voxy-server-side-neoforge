package dev.xantha.vss.client.prediction;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL45C.*;

/** Bounded, explicitly requested GPU readback. Never used by ordinary render decisions. */
final class PredictionFrameCapture {
    static final int SIDE = 128, MAX_SNAPSHOTS = 24, MAX_BYTES = 32 * 1024 * 1024;
    final Map<String, Object> report = new LinkedHashMap<>();
    final List<Map<String, Object>> snapshots = new ArrayList<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Set<Integer> programs = new HashSet<>();
    private final double x, y;
    private int bytes;

    PredictionFrameCapture(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
            throw new IllegalArgumentException("Capture coordinates must be between 0 and 1");
        this.x = x; this.y = y;
        report.put("format", 1);
        report.put("screenPointTopLeft", List.of(x, y));
        report.put("cropSide", SIDE);
        report.put("maxReadbackBytes", MAX_BYTES);
        report.put("snapshots", snapshots);
        report.put("note", "Raw attachment crops, not a full GPU pixel history. Rows start at the OpenGL bottom left; color values may be packed shader metadata, not display RGB.");
    }

    void capture(String stage, int framebuffer) {
        if (snapshots.size() >= MAX_SNAPSHOTS) { report.put("snapshotLimitReached", true); return; }
        Map<String, Object> sample = new LinkedHashMap<>();
        snapshots.add(sample);
        sample.put("stage", stage);
        sample.put("framebuffer", framebuffer);
        sample.put("state", state());
        captureProgram(glGetInteger(GL_CURRENT_PROGRAM));
        if (framebuffer == 0 || !glIsFramebuffer(framebuffer)) {
            sample.put("skipped", "Default or unavailable framebuffer"); return;
        }
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int[] packEnums = {GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS,
                GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        int[] previousPack = new int[packEnums.length];
        int previousPbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        int previousBuffer = glGetInteger(GL_READ_BUFFER);
        try {
            for (int i = 0; i < packEnums.length; i++) previousPack[i] = glGetInteger(packEnums[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int parameter : packEnums) glPixelStorei(parameter, parameter == GL_PACK_ALIGNMENT ? 1 : 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                sample.put("skipped", "Incomplete framebuffer"); return;
            }
            List<Map<String, Object>> attachments = new ArrayList<>();
            sample.put("attachments", attachments);
            // Shader packs can use high color indices even with only a few active outputs.
            int colors = glGetInteger(GL_MAX_COLOR_ATTACHMENTS);
            for (int index = -1; index < colors; index++) {
                int attachment = index < 0 ? GL_DEPTH_ATTACHMENT : GL_COLOR_ATTACHMENT0 + index;
                if (glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, attachment,
                        GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) continue;
                int texture = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, attachment,
                        GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                int level = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, attachment,
                        GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                int width = glGetTextureLevelParameteri(texture, level, GL_TEXTURE_WIDTH);
                int height = glGetTextureLevelParameteri(texture, level, GL_TEXTURE_HEIGHT);
                int samples = glGetTextureLevelParameteri(texture, level, GL_TEXTURE_SAMPLES);
                Map<String, Object> info = new LinkedHashMap<>();
                attachments.add(info);
                info.put("attachment", index < 0 ? "depth" : "color" + index);
                info.put("texture", texture); info.put("level", level);
                info.put("size", List.of(width, height));
                info.put("internalFormat", glGetTextureLevelParameteri(texture, level, GL_TEXTURE_INTERNAL_FORMAT));
                if (samples > 0 || width <= 0 || height <= 0) {
                    info.put("skipped", "Multisample or empty attachment; no resolve is performed"); continue;
                }
                int w = Math.min(SIDE, width), h = Math.min(SIDE, height);
                int px = cropOrigin(x, width, w), py = cropOrigin(1 - y, height, h);
                int components = index < 0 ? 1 : 4;
                int length = w * h * components * 4;
                if (bytes + length > MAX_BYTES) { info.put("skipped", "Readback byte limit"); continue; }
                int componentType = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, attachment,
                        GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
                boolean integer = index >= 0 && (componentType == GL_INT || componentType == GL_UNSIGNED_INT);
                int type = integer ? componentType : GL_FLOAT;
                String name = String.format(Locale.ROOT, "%02d-%s-%s.%s", snapshots.size(), stage,
                        info.get("attachment"), integer ? type == GL_INT ? "i32" : "u32" : "f32");
                ByteBuffer data = MemoryUtil.memAlloc(length).order(ByteOrder.nativeOrder());
                try {
                    if (index >= 0) glReadBuffer(attachment);
                    glReadPixels(px, py, w, h, index < 0 ? GL_DEPTH_COMPONENT : integer ? GL_RGBA_INTEGER : GL_RGBA, type, data);
                    byte[] copy = new byte[length]; data.get(copy);
                    files.put(name, copy); bytes += length;
                } finally { MemoryUtil.memFree(data); }
                info.put("file", name); info.put("crop", List.of(px, py, w, h));
                info.put("components", components); info.put("type", type);
                info.put("byteOrder", ByteOrder.nativeOrder().toString());
            }
        } finally {
            glReadBuffer(previousBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPbo);
            for (int i = 0; i < packEnums.length; i++) glPixelStorei(packEnums[i], previousPack[i]);
        }
    }

    static int cropOrigin(double point, int size, int crop) {
        return Math.max(0, Math.min(size - crop, (int) Math.floor(point * size) - crop / 2));
    }

    private void captureProgram(int program) {
        if (program == 0 || programs.size() >= 8 || !programs.add(program)) return;
        int[] count = new int[1], shaders = new int[8];
        glGetAttachedShaders(program, count, shaders);
        for (int i = 0; i < count[0]; i++) {
            int length = glGetShaderi(shaders[i], GL_SHADER_SOURCE_LENGTH);
            if (length > 1_048_576 || bytes + length > MAX_BYTES) continue;
            byte[] source = glGetShaderSource(shaders[i]).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            files.put("program-" + program + "-shader-" + shaders[i] + ".glsl", source);
            bytes += source.length;
        }
    }

    private static Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        state.put("program", program);
        if (program != 0) {
            List<Object> uniforms = new ArrayList<>();
            int count = Math.min(512, glGetProgrami(program, GL_ACTIVE_UNIFORMS));
            var size = org.lwjgl.BufferUtils.createIntBuffer(1);
            var type = org.lwjgl.BufferUtils.createIntBuffer(1);
            for (int index = 0; index < count; index++) {
                String name = glGetActiveUniform(program, index, size, type);
                int location = glGetUniformLocation(program, name);
                if (location < 0) continue; // Uniform blocks are bound by the enclosing Voxy pass.
                float[] value = new float[16]; glGetUniformfv(program, location, value);
                List<Object> values = new ArrayList<>();
                for (float number : value) values.add(Float.isFinite(number) ? (Object) number : Float.toString(number));
                uniforms.add(Map.of("name", name, "type", type.get(0), "arraySize", size.get(0), "firstElementAsFloat", values));
            }
            state.put("uniforms", uniforms);
        }
        state.put("drawFramebuffer", glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        state.put("readFramebuffer", glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
        int[] viewport = new int[4]; glGetIntegerv(GL_VIEWPORT, viewport); state.put("viewport", viewport);
        state.put("depthTest", glIsEnabled(GL_DEPTH_TEST)); state.put("depthFunc", glGetInteger(GL_DEPTH_FUNC));
        state.put("depthWrite", glGetBoolean(GL_DEPTH_WRITEMASK));
        state.put("clipDepthMode", glGetInteger(GL_CLIP_DEPTH_MODE));
        double[] range = new double[2]; glGetDoublev(GL_DEPTH_RANGE, range); state.put("depthRange", range);
        state.put("cull", glIsEnabled(GL_CULL_FACE));
        state.put("stencilTest", glIsEnabled(GL_STENCIL_TEST));
        state.put("stencilFunc", glGetInteger(GL_STENCIL_FUNC));
        state.put("stencilRef", glGetInteger(GL_STENCIL_REF));
        state.put("stencilMask", glGetInteger(GL_STENCIL_VALUE_MASK));
        List<Object> outputs = new ArrayList<>();
        for (int i = 0; i < glGetInteger(GL_MAX_DRAW_BUFFERS); i++) {
            int[] mask = new int[4]; glGetIntegeri_v(GL_COLOR_WRITEMASK, i, mask);
            outputs.add(Map.of("slot", i, "buffer", glGetInteger(GL_DRAW_BUFFER0 + i), "colorWrite", mask,
                    "blend", glIsEnabledi(GL_BLEND, i), "factors", new int[]{glGetIntegeri(GL_BLEND_SRC_RGB, i),
                    glGetIntegeri(GL_BLEND_DST_RGB, i), glGetIntegeri(GL_BLEND_SRC_ALPHA, i), glGetIntegeri(GL_BLEND_DST_ALPHA, i)},
                    "equations", new int[]{glGetIntegeri(GL_BLEND_EQUATION_RGB, i), glGetIntegeri(GL_BLEND_EQUATION_ALPHA, i)}));
        }
        state.put("outputs", outputs);
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        List<Object> textures = new ArrayList<>();
        try {
            int count = Math.min(32, glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
            for (int i = 0; i < count; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                textures.add(Map.of("unit", i, "texture2D", glGetInteger(GL_TEXTURE_BINDING_2D),
                        "texture3D", glGetInteger(GL_TEXTURE_BINDING_3D), "textureArray", glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY),
                        "textureBuffer", glGetInteger(GL_TEXTURE_BINDING_BUFFER), "sampler", glGetIntegeri(GL_SAMPLER_BINDING, i)));
            }
        } finally { glActiveTexture(active); }
        state.put("activeTexture", active); state.put("textures", textures);
        return state;
    }

    Path write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path output = Files.createTempFile(directory, "frame-", ".zip");
        report.put("readbackBytes", bytes);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.putNextEntry(new ZipEntry("report.json"));
            zip.write(new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return output;
    }
}
