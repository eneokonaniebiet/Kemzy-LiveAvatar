#version 300 es
precision mediump float;

uniform sampler2D u_AvatarTexture;
uniform sampler2D u_LiveCameraFrame;
uniform sampler2D u_SegmentationAlphaMap;

in vec2 v_MeshUV;
in vec2 v_ScreenCoord;
out vec4 fragColor;

void main() {
    vec2 screenUV = clamp(v_ScreenCoord, vec2(0.0), vec2(1.0));
    vec4 roomColor = texture(u_LiveCameraFrame, screenUV);
    float bodyAlpha = texture(u_SegmentationAlphaMap, screenUV).r;
    vec4 avatarColor = texture(u_AvatarTexture, v_MeshUV);

    // Outside the segmented person, preserve the live room. Inside, use avatar.
    fragColor = mix(roomColor, avatarColor, clamp(bodyAlpha, 0.0, 1.0));
}
