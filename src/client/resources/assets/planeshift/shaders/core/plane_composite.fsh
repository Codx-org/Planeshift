#version 330
#extension GL_ARB_separate_shader_objects : require

// The opening, decided per pixel. Paired with core/screenquad, so there is no vertex
// buffer and no geometry: every pixel of the frame asks the same question, and the ones
// that answer yes are replaced by the far side.
//
// All positions are camera-relative. What the Java side packs into DynamicTransforms:
//   ModelViewMat      inverse of the frame's view-projection
//   TextureMat[0].xyz opening centre C   [1].xyz right R   [2].xyz up U
//   TextureMat[3]     half width, half height, depth guard (blocks), how far a pixel may see
//   ColorModulator    plane normal N (either sign) in rgb, and in a the clip depth this
//                     frame's projection puts its furthest surface at
//   ModelOffset.xy    target size in pixels
//   ModelOffset.z     how much clip depth the zero to one of the depth texture spans
//
// Depth is reverse-Z: the near plane is the far end of the range and distance runs down
// to the other end, which is why a pixel nothing was drawn into reads exactly 0. Which
// range that is differs by machine — minus one to one on one, zero to one on another —
// so it is read off the projection on the Java side and handed in rather than assumed.
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
    // At the two ends of the depth range this frame actually uses, which is why they are
    // handed in rather than written here: reverse-Z runs from the near plane down to the
    // far one, and where the far one sits is minus one on one machine and zero on another.
    // Unproject at an end the range does not have and the point comes back meaningless,
    // the ray through it misses the opening, and every pixel of the doorway is thrown
    // away — the whole opening showing the wall it is cut into, on one player's machine
    // and not another's.
    float atNear = ColorModulator.a + ModelOffset.z;
    float atFar = ColorModulator.a;
    vec4 nearPoint = ModelViewMat * vec4(ndc, atNear, 1.0);
    vec4 farPoint = ModelViewMat * vec4(ndc, atFar, 1.0);
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

    // How far this pixel is looking, in blocks. Wanted twice: to widen the depth guard
    // with distance, just below, and to fade the far side out further down.
    float reach = length(dir) * t;

    if (stored != 0.0) {
        // Back into the clip range the inverse expects, by the reading the Java side took
        // from this frame's own projection: the depth texture holds zero to one whatever
        // the clip range is, and only the projection knows which zero to one it is.
        vec4 s = ModelViewMat * vec4(ndc, ColorModulator.a + stored * ModelOffset.z, 1.0);
        vec3 surface = s.xyz / s.w;

        // Widened with distance, because that is how the error behaves. Depth comes back
        // through an inverse projection in single precision, and what that costs grows with
        // how far away the surface is — while the margin being judged does not: a doorway
        // hangs a hundredth of a block in front of the wall it is on, whether that wall is
        // two blocks away or sixty. A fixed guard is generous up close and, somewhere out
        // in the room, becomes tighter than the arithmetic can hold — at which point the
        // wall behind the opening reads as being in front of it and the doorway shows
        // nothing at all, on some machines and not others. A guard that grows stays on the
        // right side of that, and is still a small fraction of a block at any distance a
        // doorway is worth drawing at, so a wall genuinely in front of one still hides it.
        float room = max(guard, reach * 0.004);

        if (dot(surface - C, N) * -sign(dot(C - origin, N)) > room) {
            discard;
        }
    }

    // How far this pixel is looking, in blocks: not how far away the plane is, but how far
    // away the part of it this ray meets is. For a doorway the two are nearly the same. For
    // the floor of a world they are not: it is under your feet and also out at the horizon,
    // and a ray that slopes down by a degree meets it thousands of blocks away through
    // nothing but sky. Judged at the plane, that sky is painted with the world below and
    // reads as a dark band sitting above the horizon.
    float range = TextureMat[3].w;

    // Alpha rather than a hard replacement: the pipeline blends, so the far side dissolves
    // into whatever this world drew rather than stopping at a line.
    float shown = clamp((range - reach) / (range * 0.5), 0.0, 1.0);

    if (shown <= 0.0) {
        discard;
    }

    fragColor = vec4(texture(Sampler0, screenUV).rgb, shown);
}
