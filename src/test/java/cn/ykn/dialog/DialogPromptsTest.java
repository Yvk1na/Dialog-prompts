package cn.ykn.dialog;

import static org.junit.jupiter.api.Assertions.*;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class DialogPromptsTest {

    @Test
    void oldClientFallsBackToChatTextInputAndSupportsSubmitAndCancel() {
        FakeBridge bridge = new FakeBridge(769);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        List<String> sent = new ArrayList<>();
        Player player = fakePlayer(UUID.randomUUID(), true, sent);

        AtomicReference<String> submitted = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean beforeCalled = new AtomicBoolean();
        AtomicBoolean afterCalled = new AtomicBoolean();

        prompts.promptText(player, DialogPrompts.TextPrompt.builder(
                        Component.text("搜索拍卖物品"),
                        Component.text("搜索内容"),
                        submitted::set)
                .beforeChatFallback(() -> beforeCalled.set(true))
                .onCancel(() -> cancelled.set(true))
                .afterChatComplete(() -> afterCalled.set(true))
                .build());

        assertTrue(beforeCalled.get());
        assertTrue(prompts.hasPendingChat(player.getUniqueId()));
        assertEquals(0, bridge.dialogsShown);
        assertFalse(sent.isEmpty());

        assertTrue(prompts.handleIncomingChatForTest(player, "  钻石剑  "));
        assertEquals("钻石剑", submitted.get());
        assertFalse(cancelled.get());
        assertTrue(afterCalled.get());
        assertFalse(prompts.hasPendingChat(player.getUniqueId()));

        // Second message is no longer captured.
        assertFalse(prompts.handleIncomingChatForTest(player, "hello"));

        // Cancel flow with Chinese keyword.
        submitted.set(null);
        afterCalled.set(false);
        prompts.promptText(player, DialogPrompts.TextPrompt.builder(
                        Component.text("搜索"),
                        Component.text("关键词"),
                        submitted::set)
                .onCancel(() -> cancelled.set(true))
                .afterChatComplete(() -> afterCalled.set(true))
                .build());

        assertTrue(prompts.handleIncomingChatForTest(player, "取消"));
        assertNull(submitted.get());
        assertTrue(cancelled.get());
        assertTrue(afterCalled.get());
    }

    @Test
    void oldClientConfirmFallsBackToChatAndRetriesUnknownWords() {
        FakeBridge bridge = new FakeBridge(768);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        List<String> sent = new ArrayList<>();
        Player player = fakePlayer(UUID.randomUUID(), true, sent);

        AtomicInteger confirms = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();

        prompts.confirm(player, DialogPrompts.ConfirmPrompt.builder(
                        Component.text("确认购买"),
                        confirms::incrementAndGet)
                .body(List.of(Component.text("花费：100 金币")))
                .onCancel(cancels::incrementAndGet)
                .build());

        assertTrue(prompts.hasPendingChat(player.getUniqueId()));
        assertTrue(sent.stream().anyMatch(line -> line.contains("确认购买")));
        assertTrue(sent.stream().anyMatch(line -> line.contains("100 金币")));

        // Unknown text keeps session alive and reminds the player.
        assertTrue(prompts.handleIncomingChatForTest(player, "maybe"));
        assertEquals(0, confirms.get());
        assertEquals(0, cancels.get());
        assertTrue(prompts.hasPendingChat(player.getUniqueId()));

        // Confirm word consumes session once.
        assertTrue(prompts.handleIncomingChatForTest(player, "确认"));
        assertEquals(1, confirms.get());
        assertEquals(0, cancels.get());
        assertFalse(prompts.hasPendingChat(player.getUniqueId()));

        // Cancel word flow.
        prompts.confirm(player, DialogPrompts.ConfirmPrompt.builder(
                        Component.text("确认取消"),
                        confirms::incrementAndGet)
                .onCancel(cancels::incrementAndGet)
                .build());
        assertTrue(prompts.handleIncomingChatForTest(player, "cancel"));
        assertEquals(1, confirms.get());
        assertEquals(1, cancels.get());
    }

    @Test
    void confirmationClickRequiresTheOwnerAndConsumesOnlyTheCurrentSession() {
        FakeBridge bridge = new FakeBridge(769);
        bridge.deferMainThread = true;
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        Player owner = fakePlayer(UUID.randomUUID(), true, new ArrayList<>());
        Player other = fakePlayer(UUID.randomUUID(), true, new ArrayList<>());
        AtomicInteger confirms = new AtomicInteger();
        var prompt = DialogPrompts.ConfirmPrompt.builder(Component.text("确认转账"), confirms::incrementAndGet).build();

        prompts.confirm(owner, prompt);
        var obsolete = prompts.confirmationClickForTest(owner);
        obsolete.accept(owner);
        prompts.confirm(owner, prompt);
        bridge.mainThreadTasks.removeFirst().run();
        assertEquals(0, confirms.get());

        var current = prompts.confirmationClickForTest(owner);
        current.accept(other);
        assertTrue(bridge.mainThreadTasks.isEmpty());
        assertTrue(prompts.hasPendingChat(owner.getUniqueId()));
        current.accept(owner);
        current.accept(owner);
        bridge.mainThreadTasks.forEach(Runnable::run);

        assertEquals(1, confirms.get());
        assertFalse(prompts.hasPendingChat(owner.getUniqueId()));
    }

    @Test
    void dialogFailureFallsBackGracefullyToChat() {
        FakeBridge bridge = new FakeBridge(771);
        bridge.failShowDialog = true;
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        List<String> sent = new ArrayList<>();
        Player player = fakePlayer(UUID.randomUUID(), true, sent);

        AtomicReference<String> result = new AtomicReference<>();
        prompts.promptText(player, DialogPrompts.TextPrompt.builder(
                        Component.text("价格"),
                        Component.text("金额"),
                        result::set)
                .build());

        assertTrue(prompts.hasPendingChat(player.getUniqueId()));
        assertEquals(1, bridge.warnings);
        assertTrue(prompts.handleIncomingChatForTest(player, "500k"));
        assertEquals("500k", result.get());
    }

    @Test
    void cancelByOwnerOnlyCancelsMatchingScope() {
        FakeBridge bridge = new FakeBridge(769);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        Player auctionPlayer = fakePlayer(UUID.randomUUID(), true, new ArrayList<>());
        Player bazaarPlayer = fakePlayer(UUID.randomUUID(), true, new ArrayList<>());

        prompts.promptText(auctionPlayer, DialogPrompts.TextPrompt.builder(
                        Component.text("AH"),
                        Component.text("搜索"),
                        s -> { })
                .owner("auctionhouse")
                .build());
        prompts.promptText(bazaarPlayer, DialogPrompts.TextPrompt.builder(
                        Component.text("BZ"),
                        Component.text("数量"),
                        s -> { })
                .owner("bazaar")
                .build());

        prompts.cancelByOwner("auctionhouse");
        assertFalse(prompts.hasPendingChat(auctionPlayer.getUniqueId()));
        assertTrue(prompts.hasPendingChat(bazaarPlayer.getUniqueId()));
    }

    @Test
    void timeoutInvalidatesPendingChatSession() {
        FakeBridge bridge = new FakeBridge(769);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        List<String> sent = new ArrayList<>();
        Player player = fakePlayer(UUID.randomUUID(), true, sent);

        AtomicBoolean submitted = new AtomicBoolean();
        prompts.promptText(player, DialogPrompts.TextPrompt.builder(
                        Component.text("数量"),
                        Component.text("数量"),
                        s -> submitted.set(true))
                .timeoutSeconds(30)
                .build());

        assertEquals(1, bridge.scheduledTimeouts.size());
        bridge.scheduledTimeouts.getFirst().run();

        assertFalse(prompts.hasPendingChat(player.getUniqueId()));
        assertFalse(prompts.handleIncomingChatForTest(player, "64"));
        assertFalse(submitted.get());
        assertTrue(sent.stream().anyMatch(line -> line.contains("超时")));
    }

    @Test
    void vanillaUiPreferenceDisablesDialogAndRunsFallbackOnModernClient() {
        FakeBridge bridge = new FakeBridge(775);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        Player player = fakePlayer(UUID.randomUUID(), true, new ArrayList<>());

        assertTrue(prompts.supportsNativeDialog(player));
        assertTrue(prompts.supportsDialog(player));

        bridge.preferVanillaUi = true;
        assertTrue(prompts.supportsNativeDialog(player));
        assertFalse(prompts.supportsDialog(player));

        AtomicBoolean fallbackRan = new AtomicBoolean();
        boolean usedDialog = prompts.showOrFallback(player, "ui-test",
                () -> {
                    fail("dialogFactory should not run when player prefers vanilla UI");
                    return null;
                },
                () -> fallbackRan.set(true));

        assertFalse(usedDialog);
        assertTrue(fallbackRan.get());
        assertEquals(0, bridge.dialogsShown);
    }

    @Test
    void dialogSizePresetsCycleAndStayWithinPixelBounds() {
        assertEquals(DialogSize.STANDARD, DialogSize.fromId(null));
        assertEquals(DialogSize.STANDARD, DialogSize.fromId("unknown"));
        assertEquals(DialogSize.COMPACT, DialogSize.fromId("COMPACT"));
        assertEquals(DialogSize.MINI, DialogSize.fromId("mini"));

        assertEquals(DialogSize.COMPACT, DialogSize.STANDARD.next());
        assertEquals(DialogSize.MINI, DialogSize.COMPACT.next());
        assertEquals(DialogSize.STANDARD, DialogSize.MINI.next());

        for (int cols : List.of(1, 2, 3, 5)) {
            for (int stdWidth : List.of(96, 108, 126, 160)) {
                DialogSize.GridLayout compact = DialogSize.COMPACT.gridLayout(cols, stdWidth, 12);
                assertTrue(compact.columns() >= 1 && compact.columns() <= 4);
                int compactTotal = compact.columns() * compact.buttonWidth() + (compact.columns() - 1) * 8;
                assertTrue(compactTotal <= 408, "compact width overflow: " + compactTotal);

                DialogSize.GridLayout mini = DialogSize.MINI.gridLayout(cols, stdWidth, 12);
                assertTrue(mini.columns() >= 1 && mini.columns() <= 2);
                int miniTotal = mini.columns() * mini.buttonWidth() + (mini.columns() - 1) * 8;
                assertTrue(miniTotal <= 292, "mini width overflow: " + miniTotal);
            }
        }

        assertEquals(408, DialogSize.COMPACT.bodyWidth(520));
        assertEquals(290, DialogSize.MINI.bodyWidth(520));
        assertFalse(DialogSize.MINI.showArtwork());
        assertTrue(DialogSize.COMPACT.showArtwork());
        assertTrue(DialogSize.STANDARD.showArtwork());
    }

    @Test
    void dialogSizePersistsToPlayerPdcAndMiniSuppressesArtwork() {
        Map<NamespacedKey, Object> pdcData = new HashMap<>();
        PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> pdcData.get(args[0]);
                    case "set" -> {
                        pdcData.put((NamespacedKey) args[0], args[2]);
                        yield null;
                    }
                    case "remove" -> {
                        pdcData.remove(args[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = fakePlayerWithPdc(UUID.randomUUID(), pdc);
        assertEquals(DialogSize.STANDARD, DialogPrompts.dialogSize(player));
        assertEquals(DialogSize.COMPACT, DialogPrompts.cycleDialogSize(player));
        assertEquals("compact", pdcData.get(DialogPrompts.DIALOG_SIZE_PREFERENCE_KEY));
        assertEquals(DialogSize.MINI, DialogPrompts.cycleDialogSize(player));
        assertEquals("mini", pdcData.get(DialogPrompts.DIALOG_SIZE_PREFERENCE_KEY));
        assertEquals(DialogSize.STANDARD, DialogPrompts.cycleDialogSize(player));
        assertNull(pdcData.get(DialogPrompts.DIALOG_SIZE_PREFERENCE_KEY));

        FakeBridge bridge = new FakeBridge(775);
        DialogPrompts prompts = DialogPrompts.forTesting(bridge);
        Set<String> packFiles = Set.of(
                "assets/skyblock_dialog_art/font/panels.json",
                "assets/skyblock_dialog_art/textures/font/bank_9_0.png",
                "assets/skyblock_dialog_art/textures/font/bank_9_1.png");
        List<Component> lines = List.of(Component.text("仅文本"));
        assertTrue(prompts.useArtwork(player, DialogArtwork.BANK, packFiles, lines));

        bridge.dialogSize = DialogSize.COMPACT;
        assertTrue(prompts.useArtwork(player, DialogArtwork.BANK, packFiles, lines));

        bridge.dialogSize = DialogSize.MINI;
        assertEquals(DialogSize.MINI, prompts.size(player));
        assertFalse(prompts.useArtwork(player, DialogArtwork.BANK, packFiles, lines));

        AtomicBoolean fallbackUsed = new AtomicBoolean();
        List<DialogBody> bodies = prompts.artworkBody(
                player, DialogArtwork.BANK, lines, () -> {
                    fallbackUsed.set(true);
                    return List.of();
                });
        assertTrue(fallbackUsed.get());
        assertTrue(bodies.isEmpty());
        assertEquals(290, prompts.size(player).bodyWidth(DialogArtwork.WIDTH));
    }

    private static Player fakePlayerWithPdc(UUID id, PersistentDataContainer pdc) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    case "getPersistentDataContainer" -> pdc;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player fakePlayer(UUID id, boolean online, List<String> sentMessages) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> online;
                    case "sendMessage" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Component component) {
                            sentMessages.add(PlainTextComponentSerializer.plainText().serialize(component));
                        }
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "FakePlayer(" + id + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FakeBridge implements DialogPrompts.RuntimeBridge {
        private final int protocol;
        private boolean preferVanillaUi;
        private DialogSize dialogSize = DialogSize.STANDARD;
        private boolean failShowDialog;
        private boolean deferMainThread;
        private int dialogsShown;
        private int warnings;
        private final List<Runnable> scheduledTimeouts = new ArrayList<>();
        private final List<Runnable> mainThreadTasks = new ArrayList<>();

        private FakeBridge(int protocol) {
            this.protocol = protocol;
        }

        @Override
        public int protocol(Player player) {
            return protocol;
        }

        @Override
        public boolean prefersVanillaUi(Player player) {
            return preferVanillaUi;
        }

        @Override
        public DialogSize dialogSize(Player player) {
            return dialogSize;
        }

        @Override
        public void forgetProtocol(UUID playerId) {
        }

        @Override
        public void clearProtocols() {
        }

        @Override
        public void showDialog(Player player, Dialog dialog) {
            if (failShowDialog) {
                throw new IllegalStateException("dialog unavailable");
            }
            dialogsShown++;
        }

        @Override
        public void runMainThread(Runnable task) {
            if (deferMainThread) mainThreadTasks.add(task);
            else task.run();
        }

        @Override
        public Runnable scheduleLater(Runnable task, long delayTicks) {
            AtomicBoolean cancelled = new AtomicBoolean();
            scheduledTimeouts.add(() -> {
                if (!cancelled.get()) {
                    task.run();
                }
            });
            return () -> cancelled.set(true);
        }

        @Override
        public void logWarning(String message, Throwable error) {
            warnings++;
        }
    }
}
