#version 300 es
precision mediump float;

// Texture units follow PortraitGlRenderer's current allocation:
// avatar=0, live RGB placeholder=1, segmentation mask=2, Y/U/V=3/4/5.
uniform sampler2D u_AvatarTexture;
uniform sampler2D u_SegmentationAlphaMap;
uniform sampler2D u_TextureY;
uniform sampler2D u_TextureU;
uniform sampler2D u_TextureV;

in vec2 v_MeshUV;
in vec2 v_ScreenCoord;
out vec4 fragColor;

void main() {
    vec2 screenUV = clamp(v_ScreenCoord, vec2(0.0), vec2(1.0));

    // CameraX YUV_420_888 planes are uploaded as normalized R8 textures.
    // Convert standard limited-range BT.601 video values to RGB.
    float ySample = texture(u_TextureY, screenUV).r;
    float uSample = texture(u_TextureU, screenUV).r;
    float vSample = texture(u_TextureV, screenUV).r;

    float y = max(0.0, (ySample * 255.0 - 16.0) / 219.0);
    float u = (uSample * 255.0 - 128.0) / 224.0;
    float v = (vSample * 255.0 - 128.0) / 224.0;

    vec3 rgbBackground;
    rgbBackground.r = y + 1.402000 * v;
    rgbBackground.g = y - 0.344136 * u - 0.714136 * v;
    rgbBackground.b = y + 1.772000 * u;
    rgbBackground = clamp(rgbBackground, 0.0, 1.0);

    float bodyAlpha = clamp(texture(u_SegmentationAlphaMap, screenUV).r, 0.0, 1.0);
    vec4 avatarColor = texture(u_AvatarTexture, clamp(v_MeshUV, 0.0, 1.0));

    // Outside the person, preserve the live room; inside, composite the avatar.
    fragColor = mix(vec4(rgbBackground, 1.0), avatarColor, bodyAlpha);
}
