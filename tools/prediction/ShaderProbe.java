import java.nio.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Standalone probe of the actual production shader strings, using a hidden GL context. */
public class ShaderProbe {
    static int program, quadBuffer, yieldTexture;
    static final int SIZE = 64;
    static final int[] RGB = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};

    public static void main(String[] args) throws Exception {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(SIZE, SIZE, "VSS shader verification", 0, 0);
        if (window == 0) throw new AssertionError("Hidden GL context unavailable");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String source = Files.readString(Path.of("src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java"));
            String fog = Files.readString(Path.of("src/main/java/dev/xantha/vss/client/prediction/PredictionFogBridge.java"));
            source = source.replace("\"\"\" + PredictionFogBridge.GLSL + \"\"\"", shader(fog, "GLSL"));
            if (args.length > 0 && args[0].equals("--legacy-wall-coverage")) {
                source = source.replace("if (vCoverageAxis == 1u) cell.x = sourceCell.x;", "")
                        .replace("if (vCoverageAxis == 2u) cell.y = sourceCell.y;", "");
            }
            program = glCreateProgram();
            glAttachShader(program, compile(GL_VERTEX_SHADER, shader(source, "TERRAIN_VERTEX")));
            glAttachShader(program, compile(GL_FRAGMENT_SHADER, shader(source, "TERRAIN_FRAGMENT")));
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
            glUseProgram(program);
            System.out.println("GL=" + glGetString(GL_VERSION) + "; renderer=" + glGetString(GL_RENDERER));
            System.out.println("PASS: production terrain vertex/fragment shaders compile and link");
            glBindVertexArray(glGenVertexArrays());
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, glGenBuffers());
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 0, 2, 3}, GL_STATIC_DRAW);
            glUniformMatrix4fv(u("ModelViewMat"), false, new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
            glUniformMatrix4fv(u("ProjMat"), false, new float[]{2,0,0,0, 0,0,0,0, 0,2,0,0, -1,-1,0,1});
            glUniform4f(u("ColorModulator"), 1, 1, 1, 1);
            glUniform1f(u("LodColorScale"), 1);
            glUniform1f(u("LodFogStart"), 1000);
            glUniform1f(u("LodFogEnd"), 2000);
            glUniform1f(u("LodHazeStart"), 1000);
            glUniform4f(u("VssPredictionFog"), 1000, 2000, 1000, 0);
            glUniform1f(u("OpaqueAlpha"), 1);
            glUniform1i(u("UseAverage"), 1);
            if (u("DirectionalTint[0]") < 0) throw new AssertionError("Missing face-light uniform");
            glUniform1fv(u("DirectionalTint[0]"), new float[]{1,1,1,1,1,1,1});
            texture2D(0, "Atlas", GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture2D(1, "Lightmap", GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture2D(2, "SpriteTable", GL_RGBA32F, 2, 2, GL_RGBA,
                    new float[]{0,0,0,0, 0,0,1,1, 1,1,1,1, .1f,.8f,.2f,1});
            yieldTexture = texture2D(3, "Yield", GL_R8, 2, 2, GL_RED, new float[]{1,1,1,1});
            quadBuffer = bufferTexture(4, "QuadPayload", GL_RGBA32UI, new int[12]);
            bufferTexture(5, "MorphDeltas", GL_R32I, new int[4]);
            glUniform1i(u("VanillaMask"), 6);
            float[] skyDepth = new float[SIZE * SIZE];
            java.util.Arrays.fill(skyDepth, 1);
            texture2D(7, "MainDepth", GL_R32F, SIZE, SIZE, GL_RED, skyDepth);
            glViewport(0, 0, SIZE, SIZE);
            glDisable(GL_DITHER);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);

            ByteBuffer average = render(1, true, false);
            for (int[] xy : new int[][]{{8,8},{55,8},{55,55},{8,55},{32,32}}) {
                expect(average, xy[0], xy[1], expected(xy[0], xy[1]), "four-corner average");
            }
            save(average, "four-corner-average.png");
            System.out.println("PASS: interpolated corner colours survive coarse sprite averaging without renormalization");

            int packedY = 33792 | (33792 << 16);
            int[] fence = {6 | (10 << 16), 10 | (6 << 16), 0, 16 | (16 << 16),
                    packedY, packedY, 1 << 20, 0xB7D7EC, 0, 0x1B7D7EC, 0xB7D7EC, 0xB7D7EC};
            ByteBuffer fine = drawWords(fence);
            expect(fine, 8, 32, new int[]{0,0,0}, "fine fence outside");
            expect(fine, 32, 32, new int[]{183,215,236}, "fine fence post");
            expect(fine, 55, 32, new int[]{0,0,0}, "fine fence outside");
            save(fine, "fine-fence-coordinates.png");
            System.out.println("PASS: 1/16-block fence geometry occupies its actual pixel footprint");

            ByteBuffer missing = render(5, true, false);
            expect(missing, 32, 32, expected(32,32), "pending sprite row");
            System.out.println("PASS: a sprite row awaiting upload falls back to the mesh colour");

            ByteBuffer mask = render(1, false, true);
            expect(mask, 8, 8, new int[]{0,0,0}, "masked merged cell");
            expect(mask, 55, 55, expected(55,55), "visible merged cell");
            save(mask, "merged-cell-mask.png");
            System.out.println("PASS: merged quad coverage bit is independent of corner 1 RGB");
            for (int axis : new int[]{1, 2}) {
                for (int plane : new int[]{1, 2}) {
                    for (int averageMode : new int[]{0, 1}) {
                        for (boolean partial : new boolean[]{false, true}) {
                            ByteBuffer wall = renderWall(axis, plane, averageMode, partial);
                            for (int x = 2; x < SIZE - 2; x++) {
                                for (int y = 2; y < SIZE - 2; y++) {
                                    int[] expected = partial && x >= SIZE / 2
                                            ? new int[]{0,0,0} : new int[]{183,215,236};
                                    expect(wall, x, y, expected, "merged wall axis=" + axis
                                            + " plane=" + plane + " average=" + averageMode);
                                }
                            }
                            save(wall, "wall-axis-" + axis + "-plane-" + plane
                                    + "-average-" + averageMode + "-partial-" + partial + ".png");
                        }
                    }
                }
            }
            System.out.println("PASS: 16 wall renders, interior/tile-edge and detail/average: owning cells visible, masked run cells discarded, no gaps");
            checkWaterAppearance();
            checkTexturesAndLighting();
            checkFluidFaces();
            for (int i = 0; i + 1 < args.length; i++) {
                if (args[i].equals("--minecraft-client")) checkVanillaMaterials(Path.of(args[i + 1]));
            }
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new AssertionError("GL error: " + error);
            System.out.println("PASS: no GL errors");
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    static ByteBuffer drawWords(int[] words) {
        faceCamera(words);
        glBindBuffer(GL_TEXTURE_BUFFER, quadBuffer);
        glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
        glClearColor(0,0,0,1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        return pixels;
    }

    // These orthographic material probes omit the face's perpendicular axis
    // from projection. Place the eye on its exterior side for normal culling.
    static void faceCamera(int[] words) {
        int axis = words[6] >>> 24 & 3;
        float side = (words[6] & (1 << 30)) == 0 ? 64 : -64;
        glUniform3f(u("TileOffset"), axis == 1 ? side : 0,
                axis == 0 ? -1024 : 0, axis == 2 ? side : 0);
    }

    static void checkTexturesAndLighting() throws Exception {
        texture2D(0,"Atlas",GL_RGBA32F,2,2,GL_RGBA,new float[]{
                .125f,.125f,.125f,1, .875f,.875f,.875f,1,
                .875f,.875f,.875f,1, .125f,.125f,.125f,1});
        texture2D(2,"SpriteTable",GL_RGBA32F,2,2,GL_RGBA,new float[]{
                0,0,0,0, 0,0,1,1, 0,0,0,0, .5f,.5f,.5f,1});
        glUniform1i(u("UseAverage"),0);
        glUniform1f(u("LodFogDensity"),0);
        glUniform1f(u("LodFogStart"),1e7f);
        glUniform1f(u("LodFogEnd"),2e7f);
        for (int step : new int[]{1,4,8,32,128}) {
            int edge = step * 2;
            // Zoom into the same two world blocks at every geometry spacing.
            glUniformMatrix4fv(u("ProjMat"),false,new float[]{
                    1,0,0,0, 0,0,0,0, 0,1,0,0, -1,-1,0,1});
            glUniform1f(u("Spacing"),step);
            int[] words = {edge << 16, edge, 0, edge | (edge << 16),
                    32768 | (32768 << 16),32768 | (32768 << 16),
                    1 | (1 << 31),0x808080,0,0x808080,0x808080,0x808080};
            ByteBuffer pixels = drawWords(words);
            expect(pixels,8,8,new int[]{32,32,32},"LOD texture scale="+step);
            expect(pixels,24,8,new int[]{224,224,224},"LOD texture detail="+step);
            save(pixels,"voxy-texture-spacing-"+step+".png");
            if (step == 128) {
                glUniformMatrix4fv(u("ProjMat"),false,new float[]{
                        2f/edge,0,0,0, 0,0,0,0, 0,2f/edge,0,0, -1,-1,0,1});
                ByteBuffer minified = drawWords(words);
                expect(minified,8,8,new int[]{128,128,128},"subpixel texture converges to average");
                expect(minified,24,8,new int[]{128,128,128},"subpixel fallback has no alias pattern");
                glUniform1f(u("MorphAmount"),1);
                expect(drawWords(words),24,8,new int[]{128,128,128},"morph keeps material coordinates stable");
                glUniform1f(u("MorphAmount"),0);
            }
        }
        float[] tints = {.5f,1,.8f,.75f,.6f,.65f,.9f};
        glUniform1fv(u("DirectionalTint[0]"),tints);
        for (int face=0;face<7;face++) {
            int axis = face < 2 ? 0 : face < 4 ? 2 : 1;
            int flags = axis << 24;
            if (face == 0) flags |= 1 << 29;
            if (face == 3 || face == 5) flags |= 1 << 30;
            if (face == 6) flags |= 1 << 27;
            int fixed = 1 | (1 << 16);
            int[] words = axis == 0
                    ? new int[]{2<<16,2,0,2|(2<<16),32768|(32768<<16),32768|(32768<<16),flags,0xC8C8C8,0,0x1C8C8C8,0xC8C8C8,0xC8C8C8}
                    : new int[]{axis==1?fixed:2<<16,axis==1?fixed:2,axis==2?fixed:2<<16,axis==2?fixed:2,
                        32772|(32772<<16),32768|(32768<<16),flags,0xC8C8C8,0,0x1C8C8C8,0xC8C8C8,0xC8C8C8};
            glUniformMatrix4fv(u("ProjMat"),false,axis==0
                    ? new float[]{1,0,0,0,0,0,0,0,0,1,0,0,-1,-1,0,1}
                    : axis==1 ? new float[]{0,0,0,0,0,2,0,0,1,0,0,0,-1,-1,0,1}
                    : new float[]{1,0,0,0,0,2,0,0,0,0,0,0,-1,-1,0,1});
            int expected = face == 0 ? 0 : Math.round(200*tints[face]);
            expect(drawWords(words),32,32,new int[]{expected,expected,expected},"direction="+face);
        }
        glUniform1fv(u("DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
        System.out.println("PASS: one-block texture scale at geometry spacings 1/4/8/32/128, subpixel fallback, exterior face lights and rejected underground face");
    }

    static void checkVanillaMaterials(Path clientJar) throws Exception {
        String[] names = {"orange_terracotta", "white_terracotta", "red_sand", "grass_block_top"};
        int[] colors = new int[names.length];
        float[] atlas = new float[64 * 16 * 4];
        float[] table = new float[5 * 2 * 4];
        try (var jar = new java.util.zip.ZipFile(clientJar.toFile())) {
            for (int material = 0; material < names.length; material++) {
                var entry = jar.getEntry("assets/minecraft/textures/block/" + names[material] + ".png");
                if (entry == null) throw new AssertionError("Missing vanilla texture " + names[material]);
                BufferedImage image;
                try (var stream = jar.getInputStream(entry)) { image = ImageIO.read(stream); }
                if (image.getWidth() != 16 || image.getHeight() != 16) throw new AssertionError("Expected vanilla 16px texture");
                double[] sums = new double[3];
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    int rgb = image.getRGB(x, y);
                    int offset = (y * 64 + material * 16 + x) * 4;
                    for (int c = 0; c < 3; c++) {
                        int channel = (rgb >>> (16 - c * 8)) & 255;
                        atlas[offset + c] = channel / 255f;
                        sums[c] += channel;
                    }
                    atlas[offset + 3] = 1;
                }
                int row = (material + 1) * 4;
                table[row] = material / 4f;
                table[row + 2] = (material + 1) / 4f;
                table[row + 3] = 1;
                for (int c = 0; c < 3; c++) {
                    table[20 + row + c] = (float) (sums[c] / 256 / 255);
                    colors[material] |= (int) Math.round(sums[c] / 256) << (16 - c * 8);
                }
                table[20 + row + 3] = 1;
            }
        }
        texture2D(0, "Atlas", GL_RGBA8, 64, 16, GL_RGBA, atlas);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 4);
        glGenerateMipmap(GL_TEXTURE_2D);
        texture2D(2, "SpriteTable", GL_RGBA32F, 5, 2, GL_RGBA, table);
        glUniform1i(u("UseAverage"), 0);
        glUniform1i(u("CellAxis"), 2);
        glUniform1f(u("LodFogDensity"), 0);
        glUniform1f(u("LodFogStart"), 1e7f);
        glUniform1f(u("LodFogEnd"), 2e7f);
        for (int material = 0; material < names.length; material++) {
            ByteBuffer reference = null;
            int rgb = colors[material];
            for (int step : new int[]{1,4,8,32,128}) {
                int edge = step * 2;
                glUniform1f(u("Spacing"), step);
                int[] words = {edge << 16, edge, 0, edge | (edge << 16),
                        32768 | (32768 << 16), 32768 | (32768 << 16),
                        material + 1 | (1 << 31), rgb, 0, rgb, rgb, rgb};
                for (float span : new float[]{2, 8, 16, 31, 48, 64, 128, 256}) {
                    if (span > edge) continue;
                    glUniformMatrix4fv(u("ProjMat"), false, new float[]{
                            2/span,0,0,0, 0,0,0,0, 0,2/span,0,0, -1,-1,0,1});
                    ByteBuffer pixels = drawWords(words);
                    for (int c = 0; c < 3; c++) {
                        long sum = 0;
                        for (int p = 0; p < SIZE * SIZE; p++) sum += pixels.get(p * 4 + c) & 255;
                        double mean = sum / (double) (SIZE * SIZE);
                        int expected = rgb >>> (16 - c * 8) & 255;
                        if (Math.abs(mean - expected) > 5) throw new AssertionError(names[material]
                                + " changed colour at spacing=" + step + ", view=" + span
                                + ", channel=" + c + ": expected " + expected + ", got " + mean);
                    }
                    if (span == 2) {
                        if (reference == null) reference = pixels;
                        else for (int p = 0; p < SIZE * SIZE * 4; p++) {
                            if (reference.get(p) != pixels.get(p)) throw new AssertionError("LOD changed block texture scale");
                        }
                    }
                    if (step == 128 && (span == 2 || span == 256)) {
                        save(pixels, "vanilla-" + names[material] + (span == 2 ? "-zoom.png" : "-distant.png"));
                    }
                }
            }
        }
        System.out.println("PASS: vanilla orange/white terracotta, red sand and grass keep their RGB across LOD and zoom; adjacent atlas sprites cannot bleach terrain");
    }

    static void checkFluidFaces() {
        glUniform1f(u("Spacing"),16);
        int checks=0;
        for (int axis : new int[]{1,2}) for (int side : new int[]{-1,1}) {
            for (int kind=0;kind<4;kind++) for (int edge : new int[]{16,17}) {
                int fixed=edge|(edge<<16);
                int flags=(axis<<24)|(kind<<22);
                if(side>0)flags|=1<<30;
                int[] words={axis==1?fixed:2<<16,axis==1?fixed:2,axis==2?fixed:2<<16,axis==2?fixed:2,
                        32772|(32772<<16),32768|(32768<<16),flags,0xB7D7EC,0,0x1B7D7EC,0xB7D7EC,0xB7D7EC};
                glUniformMatrix4fv(u("ProjMat"),false,axis==1
                        ? new float[]{0,0,0,0,0,2,0,0,1,0,0,0,-1,-1,0,1}
                        : new float[]{1,0,0,0,0,2,0,0,0,0,0,0,-1,-1,0,1});
                expect(drawWords(words),32,32,new int[]{183,215,236},
                        "face axis="+axis+" side="+side+" kind="+kind+" edge="+edge);
                checks++;
            }
        }
        System.out.println("PASS: "+checks+" solid/water/lava/ice faces survive at and beside section boundaries; per-pixel handoff is tested by PredictionRenderTargetGpuTest");
    }

    static void checkWaterAppearance() throws Exception {
        texture2D(0, "Atlas", GL_RGBA32F, 1, 1, GL_RGBA, new float[]{.5f,.5f,.5f,.6f});
        texture2D(2, "SpriteTable", GL_RGBA32F, 2, 2, GL_RGBA,
                new float[]{0,0,0,0, 0,0,1,1, 1,1,1,1, .5f,.5f,.5f,.6f});
        glUniformMatrix4fv(u("ProjMat"), false, new float[]{2,0,0,0, 0,0,0,0, 0,2,0,0, -1,-1,0,1});
        glUniform1f(u("Spacing"), 1);
        glUniform1i(u("CellAxis"), 2);
        glUniform1f(u("LodFogStart"), 3000);
        glUniform1f(u("LodFogEnd"), 4096);
        glUniform1f(u("LodHazeStart"), 2048);
        glUniform1f(u("LodFogDensity"), .00017f);
        glUniform4f(u("LodFogColor"), 1,1,1,1);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, yieldTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2,2,GL_RED,GL_FLOAT,new float[]{1,1,1,1});
        for (int average : new int[]{0,1}) {
            for (int color : new int[]{0x204080, 0x408020}) {
                int[] words = {1 << 16, 1, 0, 1 | (1 << 16),
                        33024 | (33024 << 16), 33024 | (33024 << 16),
                        1 | (1 << 22), color, 0,
                        color | (1 << 24), color, color};
                glBindBuffer(GL_TEXTURE_BUFFER, quadBuffer);
                glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
                glUniform1i(u("UseAverage"), average);
                faceCamera(words);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
                ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
                glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                expect(pixels, 32,32, new int[]{color >> 16 & 255, color >> 8 & 255, color & 255},
                        "water biome colour, average=" + average);
                save(pixels,"water-tint-" + Integer.toHexString(color) + "-average-" + average + ".png");
            }
        }
        System.out.println("PASS: two biome water tints match texture/average paths; no added prediction haze inside the handoff distance");
    }

    static ByteBuffer renderWall(int axis, int plane, int average, boolean partial) {
        int fixed = plane | (plane << 16);
        int[] words = {axis == 1 ? fixed : 2 << 16, axis == 1 ? fixed : 2,
                axis == 2 ? fixed : 2 << 16, axis == 2 ? fixed : 2,
                32772 | (32772 << 16), 32768 | (32768 << 16),
                (axis << 24), 0xB7D7EC,
                axis == 1 ? plane - 1 : (plane - 1) * 2,
                0xB7D7EC, 0xB7D7EC, 0xB7D7EC};
        glUniformMatrix4fv(u("ProjMat"), false, axis == 1
                ? new float[]{0,0,0,0, 0,2,0,0, 1,0,0,0, -1,-1,0,1}
                : new float[]{1,0,0,0, 0,2,0,0, 0,0,0,0, -1,-1,0,1});
        glUniform1f(u("Spacing"), 1);
        glUniform1i(u("CellAxis"), 2);
        glUniform1i(u("UseAverage"), average);
        faceCamera(words);
        glBindBuffer(GL_TEXTURE_BUFFER, quadBuffer);
        glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
        float[] mask = new float[4];
        int origin = words[8];
        mask[origin] = 1;
        mask[origin + (axis == 1 ? 2 : 1)] = partial ? 0 : 1;
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, yieldTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2,2,GL_RED,GL_FLOAT,mask);
        glClearColor(0,0,0,1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        return pixels;
    }

    static ByteBuffer render(int sprite, boolean sourceCoverage, boolean hole) {
        int[] words = {1 << 16, 1, 0, 1 | (1 << 16),
                33024 | (33024 << 16), 33024 | (33024 << 16),
                sprite, RGB[0], 0,
                RGB[1] | (sourceCoverage ? 1 << 24 : 0), RGB[2], RGB[3]};
        glBindBuffer(GL_TEXTURE_BUFFER, quadBuffer);
        glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
        glUniform1f(u("Spacing"), .5f);
        glUniform1i(u("CellAxis"), 2);
        faceCamera(words);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, yieldTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2,2,GL_RED,GL_FLOAT,
                new float[]{hole ? 0 : 1,1,1,1});
        glClearColor(0,0,0,1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        return pixels;
    }

    static int[] expected(int px, int py) {
        double x = (px + .5) / SIZE, y = (py + .5) / SIZE;
        double r = 1 - x, g = x >= y ? x - y : y - x, b = Math.min(x,y);
        return new int[]{(int)Math.round(r*255), (int)Math.round(g*255), (int)Math.round(b*255)};
    }

    static void expect(ByteBuffer pixels, int x, int y, int[] rgb, String label) {
        for (int c=0;c<3;c++) {
            int actual = pixels.get((y*SIZE+x)*4+c) & 255;
            if (Math.abs(actual-rgb[c])>3) throw new AssertionError(label+" at "+x+","+y+" channel "+c+": expected "+rgb[c]+", got "+actual);
        }
    }

    static void save(ByteBuffer pixels, String name) throws Exception {
        BufferedImage image = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            int p=(y*SIZE+x)*4;
            image.setRGB(x,SIZE-1-y,0xFF000000|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));
        }
        ImageIO.write(image,"png",Path.of("build/reports/prediction-visual",name).toFile());
    }

    static int u(String name) { return glGetUniformLocation(program,name); }
    static String shader(String source, String field) {
        int begin=source.indexOf("\"\"\"", source.indexOf("String " + field))+3;
        return source.substring(begin,source.indexOf("\"\"\"",begin));
    }
    static int compile(int kind, String text) {
        int shader=glCreateShader(kind);
        glShaderSource(shader,text);
        glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }
    static int texture2D(int unit,String name,int internal,int w,int h,int format,float[] data) {
        glActiveTexture(GL_TEXTURE0+unit);
        int texture=glGenTextures();
        glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,internal,w,h,0,format,GL_FLOAT,data);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glUniform1i(u(name),unit);
        return texture;
    }
    static int bufferTexture(int unit,String name,int internal,int[] data) {
        glActiveTexture(GL_TEXTURE0+unit);
        glBindTexture(GL_TEXTURE_BUFFER,glGenTextures());
        int buffer=glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER,buffer);
        glBufferData(GL_TEXTURE_BUFFER,data,GL_STATIC_DRAW);
        glTexBuffer(GL_TEXTURE_BUFFER,internal,buffer);
        glUniform1i(u(name),unit);
        return buffer;
    }
}
