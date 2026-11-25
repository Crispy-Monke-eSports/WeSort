package com.porter.wesort.compat;

import static org.junit.jupiter.api.Assertions.*;

import com.porter.wesort.data.CanonicalSnbt;
import java.util.List;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

class NativeSnbtTest {
    @Test
    void nativeTreesRoundTripWithTypesAndOrder() throws Exception {
        for (String input : List.of(
                "{b:-128b,s:32767s,i:2147483647,l:9007199254740993L,f:-0.0f,d:0.125d}",
                "{b:[B;-128b,0b,127b],i:[I;0,2147483647],l:[L;-9223372036854775808L]}",
                "{x:[1,\"two\",{z:3s}],text:\"quoted\\\" \\\\ ☃\",empty:[]}",
                "{id:\"minecraft:diamond\",count:64,components:{\"minecraft:custom_data\":{n:1b}}}")) {
            var nativeTag = TagParser.create(NbtOps.INSTANCE).parseFully(input);
            var encoded = NbtFingerprint.encode(nativeTag);
            assertEquals(nativeTag, NbtFingerprint.decode(encoded), input);
            assertEquals(encoded, CanonicalSnbt.canonicalize(encoded));
        }
    }

    @Test
    void numericWidthAndCompoundOrderRemainDistinct() throws Exception {
        assertEquals(NbtFingerprint.encode(TagParser.create(NbtOps.INSTANCE).parseFully("{b:2,a:1}")),
                NbtFingerprint.encode(TagParser.create(NbtOps.INSTANCE).parseFully("{a:1,b:2}")));
        assertNotEquals(NbtFingerprint.encode(TagParser.create(NbtOps.INSTANCE).parseFully("1b")),
                NbtFingerprint.encode(TagParser.create(NbtOps.INSTANCE).parseFully("1")));
    }
}
