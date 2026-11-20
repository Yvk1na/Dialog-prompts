package cn.ykn.dialog;

import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DialogResourcePackTest {
    @Test
    void appliesTheCurrentPackAfterJoinOrOnlineResend(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("resource_pack.zip");
        var player = UUID.randomUUID();
        var pack = new DialogResourcePack(archive);
        String font = "assets/skyblock_dialog_art/font/panels.json";
        String tile0 = "assets/skyblock_dialog_art/textures/font/bank_9_0.png";
        String tile1 = "assets/skyblock_dialog_art/textures/font/bank_9_1.png";
        writePack(archive, font, 1);
        var before = DialogResourcePack.selfHostedPackId(archive);
        pack.joined(player, false);
        pack.status(player, before, Status.ACCEPTED);
        pack.status(player, before, Status.DOWNLOADED);
        pack.status(player, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED);
        assertEquals(Set.of(), pack.loadedFiles(player));

        pack.status(player, before, Status.SUCCESSFULLY_LOADED);
        assertEquals(Set.of(font), pack.loadedFiles(player));

        writePack(archive, font, 32, tile0, tile1);
        var after = DialogResourcePack.selfHostedPackId(archive);
        assertNotEquals(before, after);
        pack.status(player, before, Status.SUCCESSFULLY_LOADED);
        assertEquals(Set.of(), pack.loadedFiles(player));
        pack.status(player, after, Status.SUCCESSFULLY_LOADED);
        pack.status(player, before, Status.DISCARDED);
        assertEquals(Set.of(font, tile0, tile1), pack.loadedFiles(player));

        pack.status(player, after, Status.FAILED_RELOAD);
        assertEquals(Set.of(), pack.loadedFiles(player));
    }

    @Test
    void requiresPackLoadAtLoginAndClearsWhenArchiveMissing(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("resource_pack.zip");
        var player = UUID.randomUUID();
        var pack = new DialogResourcePack(archive);
        String font = "assets/skyblock_dialog_art/font/panels.json";
        String tile = "assets/skyblock_dialog_art/textures/font/level_9_0.png";

        writePack(archive, font, 2, tile);
        pack.joined(player, false);
        assertEquals(Set.of(), pack.loadedFiles(player));

        pack.joined(player, true);
        assertEquals(Set.of(font, tile), pack.loadedFiles(player));

        Files.delete(archive);
        assertEquals(Set.of(), pack.loadedFiles(player));
    }

    private static void writePack(Path archive, String entry, int size, String... textures) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(entry));
            for (int index = 0; index < size; index++) zip.write(index);
            for (String texture : textures) {
                zip.putNextEntry(new ZipEntry(texture));
                zip.write(1);
            }
        }
    }
}
