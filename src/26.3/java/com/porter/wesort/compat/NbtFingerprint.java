package com.porter.wesort.compat;

import com.porter.wesort.snbt.SnbtCodec;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/** Native NBT is authoritative. Canonical SNBT preserves types and logical list order. */
public final class NbtFingerprint {
    private NbtFingerprint() {}

    public static String encode(Tag tag) {
        String canonical = SnbtCodec.canonicalize(tag.toString());
        if (!tag.equals(parseNative(canonical)))
            throw new IllegalArgumentException("Native NBT cannot round-trip through canonical SNBT");
        return canonical;
    }

    public static Tag decode(String value) {
        String canonical = SnbtCodec.canonicalize(value);
        Tag tag = parseNative(canonical);
        if (!canonical.equals(encode(tag)))
            throw new IllegalArgumentException("SNBT differs from its native typed representation");
        return tag;
    }

    private static Tag parseNative(String value) {
        try {
            return TagParser.create(NbtOps.INSTANCE).parseFully(value);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalArgumentException("Invalid native SNBT", failure);
        }
    }
}
