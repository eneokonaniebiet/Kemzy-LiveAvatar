#version 300 es
precision highp float;

// Prototype asset shader. It is not wired into the existing portrait renderer yet.
// Current native face mesh has no bone-ID/weight attributes in this layout.
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec2 a_TexCoord;
layout(location = 2) in float a_BoneId;
layout(location = 3) in float a_SkinWeight;

uniform mat4 u_BoneMatrices[4];
uniform mat4 u_MvpMatrix;

out vec2 v_MeshUV;
out vec2 v_ScreenCoord;

void main() {
    int boneId = clamp(int(a_BoneId + 0.5), 0, 3);
    vec4 restPosition = vec4(a_Position, 1.0);
    vec4 transformedPosition = u_BoneMatrices[boneId] * restPosition;
    float weight = clamp(a_SkinWeight, 0.0, 1.0);
    vec4 skinnedPosition = mix(restPosition, transformedPosition, weight);

    gl_Position = u_MvpMatrix * skinnedPosition;
    v_MeshUV = a_TexCoord;
    vec2 ndc = gl_Position.xy / max(abs(gl_Position.w), 0.00001);
    v_ScreenCoord = ndc * 0.5 + 0.5;
}
