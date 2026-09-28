#version 330
#extension GL_ARB_separate_shader_objects : require

// The opening, decided per pixel. Paired with core/screenquad, so there is no vertex
// buffer and no geometry: every pixel of the frame asks the same question, and the ones
// that answer yes are replaced by the far side.
//
// All positions are camera-relative. What the Java side packs into DynamicTransforms:
//   ModelViewMat      inverse of the frame's view-projection
//   TextureMat[0].xyz opening centre C   [1].xyz right R   [2].xyz up U
//   TextureMat[3]     half width, half height, depth guard (blocks), how solidly to show
//   ColorModulator    plane normal N (either sign) in rgb
//   ModelOffset.xy    target size in pixels
//
// Depth is reverse-Z over minus one to one: the near plane is at ndc z 1 and distance
// runs to -1, so ndc z is -1 + 2 * near / distance. The depth texture holds half of that
// shifted into zero to one — near/distance — which is why a pixel nothing was drawn into
// reads exactly 0. Measured from the projection itself rather than assumed; 26.2 ran
// zero to one and the arithmetic differs.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0; // the far side, drawn from the carried-through eye
uniform sampler2D Sampler1; // this world's depth, copied before the pass

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 screenUV = gl_FragCoord.xy / ModelOffset.xy;
    vec2 ndc = screenUV * 2.0 - 1.0;

    // Two points on this pixel's view ray, rather than one point treated as a direction
    // from the origin. The eye is only at the origin when the projection is a plain
    // perspective; the frame's carries the view bob, whose translation moves the point
    // the rays actually meet at. Taking two points asks the matrix where that is instead
    // of assuming, and so holds still while walking.
    vec4 nearPoint = ModelViewMat * vec4(ndc, 1.0, 1.0);
    vec4 farPoint = ModelViewMat * vec4(ndc, -1.0, 1.0);
    vec3 origin = nearPoint.xyz / nearPoint.w;
    vec3 dir = farPoint.xyz / farPoint.w - origin;

    vec3 N = ColorModulator.rgb;
    vec3 C = TextureMat[0].xyz;
    vec3 R = TextureMat[1].xyz;
    vec3 U = TextureMat[2].xyz;
    float guard = TextureMat[3].z;

    float denom = dot(dir, N);

    if (abs(denom) < 1e-9) {
        discard; // Looking along the surface: there is no opening to see.
    }

    float t = dot(C - origin, N) / denom;

    if (t <= 0.0) {
        discard; // The plane is behind the eye.
    }

    vec3 hit = origin + dir * t - C;

    if (abs(dot(hit, R)) > TextureMat[3].x || abs(dot(hit, U)) > TextureMat[3].y) {
        discard; // Past the edge of the opening.
    }

    // Anything of this world in front of the plane stays in front: a wall between the
    // eye and the opening, a mob standing halfway through it.
    float stored = texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r;

    if (stored != 0.0) {
        // Back into the clip range the inverse expects. Feeding the stored value
        // straight in puts every surface at the near plane, which reads as everything
        // blocking the opening.
        vec4 s = ModelViewMat * vec4(ndc, stored * 2.0 - 1.0, 1.0);
        vec3 surface = s.xyz / s.w;

        if (dot(surface - C, N) * -sign(dot(C - origin, N)) > guard) {
            discard;
        }
    }

    // Alpha rather than a hard replacement: the pipeline blends, so a far side at the
    // edge of its range dissolves into whatever this world drew instead of vanishing.
    fragColor = vec4(texture(Sampler0, screenUV).rgb, TextureMat[3].w);
}
