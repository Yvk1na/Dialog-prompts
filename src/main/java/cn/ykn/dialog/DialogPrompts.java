package cn.ykn.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Shared prompt and confirmation helper for Paper 1.21.6+ plugins.
 *
 * <p>Uses Paper's native Dialog API whenever the player's client supports protocol 771+,
 * and automatically falls back to chat-based input/confirmation for older clients or
 * whenever Dialog rendering fails.
 */
public final class DialogPrompts implements Listener, AutoCloseable {
    public static final int PROTO_DIALOG = DialogProtocolSupport.PROTO_DIALOG;
    public static final NamespacedKey VANILLA_UI_PREFERENCE_KEY =
            Objects.requireNonNull(NamespacedKey.fromString("skyblock-core:prefer_vanilla_ui"));
    private static final String INPUT_KEY = "val";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_INPUT_WIDTH = 260;

    private static final Set<String> CONFIRM_WORDS = Set.of(
            "confirm", "yes", "y", "ok", "确认", "確認", "确定", "確定", "是");
    private static final Set<String> CANCEL_WORDS = Set.of(
            "cancel", "no", "n", "取消", "否");

    interface RuntimeBridge {
        int protocol(Player player);

        default boolean prefersVanillaUi(Player player) {
            return false;
        }

        void forgetProtocol(UUID playerId);

        void clearProtocols();

        void showDialog(Player player, Dialog dialog);

        void runMainThread(Runnable task);

        Runnable scheduleLater(Runnable task, long delayTicks);

        void logWarning(String message, Throwable error);
    }

    private enum SessionKind {
        DIALOG,
        CHAT_TEXT,
        CHAT_CONFIRM
    }

    private static final class ActiveSession {
        final UUID playerId;
        final Object owner;
        final SessionKind kind;
        final AtomicBoolean handled = new AtomicBoolean(false);
        final Consumer<String> onTextSubmit;
        final Runnable onConfirm;
        final Runnable onCancel;
        final Runnable afterChatComplete;
        final Component chatCancelMessage;
        final Component chatTimeoutMessage;
        final Consumer<Runnable> executor;
        volatile Runnable cancelTimeout;

        ActiveSession(
                UUID playerId,
                Object owner,
                SessionKind kind,
                Consumer<String> onTextSubmit,
                Runnable onConfirm,
                Runnable onCancel,
                Runnable afterChatComplete,
                Component chatCancelMessage,
                Component chatTimeoutMessage,
                Consumer<Runnable> executor) {
            this.playerId = playerId;
            this.owner = owner;
            this.kind = kind;
            this.onTextSubmit = onTextSubmit;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.afterChatComplete = afterChatComplete;
            this.chatCancelMessage = chatCancelMessage;
            this.chatTimeoutMessage = chatTimeoutMessage;
            this.executor = executor;
        }

        boolean expectsChat() {
            return (kind == SessionKind.CHAT_TEXT || kind == SessionKind.CHAT_CONFIRM) && !handled.get();
        }

        boolean tryConsume() {
            boolean won = handled.compareAndSet(false, true);
            if (won) {
                cancelTimer();
            }
            return won;
        }

        void invalidate() {
            handled.set(true);
            cancelTimer();
        }

        private void cancelTimer() {
            Runnable cancel = cancelTimeout;
            cancelTimeout = null;
            if (cancel != null) {
                cancel.run();
            }
        }
    }

    private final RuntimeBridge bridge;
    private final Map<UUID, ActiveSession> sessions = new ConcurrentHashMap<>();
    private final boolean registeredListener;
    private DialogResourcePack resourcePack;
    private Runnable unregisterPackPackets;

    private DialogPrompts(RuntimeBridge bridge, boolean registeredListener) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.registeredListener = registeredListener;
    }

    /** Creates and registers a {@link DialogPrompts} instance owned by {@code plugin}. */
    public static DialogPrompts create(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        DialogProtocolSupport protocolSupport = new DialogProtocolSupport(plugin.getLogger());
        RuntimeBridge bridge = new BukkitRuntimeBridge(plugin, protocolSupport);
        DialogPrompts prompts = new DialogPrompts(bridge, true);
        var manager = plugin.getServer().getPluginManager();
        manager.registerEvents(prompts, plugin);
        if (manager.isPluginEnabled("CraftEngine")) {
            prompts.resourcePack = DialogResourcePack.forCraftEngine(plugin.getServer());
            manager.registerEvents(prompts.resourcePack, plugin);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                prompts.resourcePack.joined(online.getUniqueId(), online.hasResourcePack());
            }
            if (manager.isPluginEnabled("packetevents")) {
                try {
                    prompts.unregisterPackPackets = new DialogPackPackets(plugin, prompts.resourcePack);
                } catch (LinkageError ignored) {
                    // Fallback to Bukkit's PlayerResourcePackStatusEvent when packetevents is hidden.
                }
            }
        }
        return prompts;
    }

    /** Package-private constructor for deterministic unit tests. */
    static DialogPrompts forTesting(RuntimeBridge bridge) {
        return new DialogPrompts(bridge, false);
    }

    public int protocol(Player player) {
        return bridge.protocol(player);
    }

    /** Returns whether the player's client protocol natively supports Paper Dialogs. */
    public boolean supportsNativeDialog(Player player) {
        return player != null && bridge.protocol(player) >= PROTO_DIALOG;
    }

    /** Returns whether Dialog UI is active for this player (protocol 771+ and not set to vanilla UI). */
    public boolean supportsDialog(Player player) {
        return supportsNativeDialog(player) && !bridge.prefersVanillaUi(player);
    }

    /** Reads the persistent per-player UI mode preference stored on the player's PDC. */
    public static boolean isPreferVanillaUi(Player player) {
        if (player == null) {
            return false;
        }
        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            return pdc != null && pdc.getOrDefault(VANILLA_UI_PREFERENCE_KEY, PersistentDataType.BYTE, (byte) 0) != 0;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Updates the persistent per-player UI mode preference stored on the player's PDC. */
    public static void setPreferVanillaUi(Player player, boolean preferVanilla) {
        if (player == null) {
            return;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc == null) {
            return;
        }
        if (preferVanilla) {
            pdc.set(VANILLA_UI_PREFERENCE_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.remove(VANILLA_UI_PREFERENCE_KEY);
        }
    }

    public Set<String> loadedPackFiles(Player player) {
        return resourcePack == null ? Set.of() : resourcePack.loadedFiles(player.getUniqueId());
    }

    public List<DialogBody> artworkBody(Player player, DialogArtwork artwork, List<Component> lines) {
        return artworkBody(player, artwork, lines,
                () -> lines.stream().filter(Objects::nonNull).map(line -> (DialogBody) DialogBody.plainMessage(line)).toList());
    }

    public List<DialogBody> artworkBody(Player player, DialogArtwork artwork, List<Component> lines,
                                       Supplier<List<DialogBody>> fallback) {
        if (artwork != null && artwork.isAvailable(loadedPackFiles(player), lines)) {
            return List.of(DialogBody.plainMessage(artwork.overlay(lines), DialogArtwork.WIDTH + 16));
        }
        return fallback.get();
    }

    /**
     * Shows a custom Paper {@link Dialog} on supported clients, or runs {@code fallback}
     * on older clients or if Dialog creation/display fails.
     */
    public boolean showOrFallback(Player player, Object owner, Supplier<Dialog> dialogFactory, Runnable fallback) {
        return showOrFallback(player, owner, session -> dialogFactory.get(), fallback);
    }

    public record MenuButton(Component label, Component tooltip, Runnable action) {
        public MenuButton(String label, Runnable action) {
            this(Component.text(label), null, action);
        }
    }

    public boolean showMenu(Player player, Object owner, Component title, List<Component> body,
                            List<MenuButton> buttons, int columns, Runnable fallback) {
        return showMenu(player, owner, title, body, buttons, columns, fallback, null);
    }

    public boolean showMenu(Player player, Object owner, Component title, List<Component> body,
                            List<MenuButton> buttons, int columns, Runnable fallback, DialogArtwork artwork) {
        int buttonWidth = columns >= 3 ? 102 : 150;
        return showOrFallback(player, owner, session -> {
            List<ActionButton> actions = new ArrayList<>();
            for (MenuButton button : buttons) {
                ActionButton.Builder builder = ActionButton.builder(button.label()).width(buttonWidth);
                if (button.tooltip() != null) builder.tooltip(button.tooltip());
                builder.action(DialogAction.customClick(
                        (response, audience) -> executeForSession(player, session, audience, button.action()),
                        callbackOptions(600)));
                actions.add(builder.build());
            }
            DialogBase base = DialogBase.builder(title).pause(false).canCloseWithEscape(true)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(artworkBody(player, artwork, body)).build();
            ActionButton close = ActionButton.builder(Component.text("关闭")).build();
            return Dialog.create(factory -> factory.empty().base(base)
                    .type(DialogType.multiAction(actions, close, columns)));
        }, fallback);
    }

    private boolean showOrFallback(Player player, Object owner, Function<ActiveSession, Dialog> dialogFactory, Runnable fallback) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dialogFactory, "dialogFactory");
        if (supportsDialog(player)) {
            ActiveSession session = registerSession(new ActiveSession(
                    player.getUniqueId(), owner, SessionKind.DIALOG,
                    null, null, null, null, null, null, null));
            try {
                Dialog dialog = Objects.requireNonNull(dialogFactory.apply(session), "dialogFactory returned null");
                bridge.showDialog(player, dialog);
                return true;
            } catch (RuntimeException | LinkageError e) {
                sessions.remove(player.getUniqueId(), session);
                session.invalidate();
                bridge.logWarning("Dialog 显示失败，退回聊天栏交互: " + e.getMessage(), e);
            }
        }
        if (fallback != null) {
            fallback.run();
        }
        return false;
    }

    /**
     * Prompts the player for single-line text input using Paper Dialog when supported,
     * falling back to chat input on older clients.
     */
    public void promptText(Player player, TextPrompt prompt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prompt, "prompt");

        if (supportsDialog(player)) {
            ActiveSession session = registerSession(new ActiveSession(
                    player.getUniqueId(),
                    prompt.owner,
                    SessionKind.DIALOG,
                    prompt.onSubmit,
                    null,
                    prompt.onCancel,
                    null,
                    null,
                    null,
                    prompt.executor));
            try {
                Dialog dialog = buildTextDialog(player, session, prompt);
                bridge.showDialog(player, dialog);
                return;
            } catch (RuntimeException | LinkageError e) {
                sessions.remove(player.getUniqueId(), session);
                session.invalidate();
                bridge.logWarning("Dialog 输入开启失败，退回聊天栏捕获: " + e.getMessage(), e);
            }
        }

        Component chatPrompt = prompt.chatPrompt != null
                ? prompt.chatPrompt
                : Component.text("请在聊天栏输入「", NamedTextColor.YELLOW)
                        .append(prompt.title)
                        .append(Component.text("」（输入 cancel 或 取消 放弃）：", NamedTextColor.YELLOW));
        startChatInput(
                player,
                prompt.owner,
                chatPrompt,
                prompt.chatCancelMessage,
                prompt.chatTimeoutMessage,
                prompt.timeoutSeconds,
                prompt.beforeChatFallback,
                prompt.onSubmit,
                prompt.onCancel,
                prompt.afterChatComplete);
    }

    /**
     * Shows a confirmation dialog ({@link DialogType#confirmation}) on 1.21.6+ clients,
     * falling back to clickable chat buttons + chat confirmation words on older clients.
     */
    public void confirm(Player player, ConfirmPrompt prompt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prompt, "prompt");

        if (supportsDialog(player)) {
            ActiveSession session = registerSession(new ActiveSession(
                    player.getUniqueId(),
                    prompt.owner,
                    SessionKind.DIALOG,
                    null,
                    prompt.onConfirm,
                    prompt.onCancel,
                    null,
                    null,
                    null,
                    prompt.executor));
            try {
                Dialog dialog = buildConfirmDialog(player, session, prompt);
                bridge.showDialog(player, dialog);
                return;
            } catch (RuntimeException | LinkageError e) {
                sessions.remove(player.getUniqueId(), session);
                session.invalidate();
                bridge.logWarning("确认 Dialog 开启失败，退回聊天栏确认: " + e.getMessage(), e);
            }
        }

        startChatConfirm(player, prompt);
    }

    /** Starts a chat-only input capture session (useful as fallback for custom multiline dialogs). */
    public void startChatInput(
            Player player,
            Object owner,
            Component chatPrompt,
            Component chatCancelMessage,
            Component chatTimeoutMessage,
            int timeoutSeconds,
            Runnable beforeChatFallback,
            Consumer<String> onSubmit,
            Runnable onCancel,
            Runnable afterChatComplete) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(onSubmit, "onSubmit");

        ActiveSession session = registerSession(new ActiveSession(
                player.getUniqueId(),
                owner,
                SessionKind.CHAT_TEXT,
                onSubmit,
                null,
                onCancel,
                afterChatComplete,
                chatCancelMessage,
                chatTimeoutMessage,
                null));

        if (beforeChatFallback != null) {
            beforeChatFallback.run();
        }
        if (chatPrompt != null) {
            player.sendMessage(chatPrompt);
        }
        armTimeout(player, session, timeoutSeconds);
    }

    public void cancel(Player player) {
        if (player != null) {
            cancel(player.getUniqueId());
        }
    }

    public void cancel(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ActiveSession existing = sessions.remove(playerId);
        if (existing != null) {
            existing.invalidate();
        }
    }

    public void cancelByOwner(Object owner) {
        if (owner == null) {
            return;
        }
        for (Map.Entry<UUID, ActiveSession> entry : sessions.entrySet()) {
            ActiveSession session = entry.getValue();
            if (Objects.equals(session.owner, owner) && sessions.remove(entry.getKey(), session)) {
                session.invalidate();
            }
        }
    }

    public boolean hasPendingChat(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        ActiveSession session = sessions.get(playerId);
        return session != null && session.expectsChat();
    }

    @Override
    public void close() {
        for (UUID id : sessions.keySet().toArray(UUID[]::new)) {
            cancel(id);
        }
        bridge.clearProtocols();
        if (registeredListener) {
            HandlerList.unregisterAll(this);
            if (unregisterPackPackets != null) unregisterPackPackets.run();
            if (resourcePack != null) HandlerList.unregisterAll(resourcePack);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ActiveSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.expectsChat()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        bridge.runMainThread(() -> handleChatLine(player, session, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancel(id);
        bridge.forgetProtocol(id);
    }

    /** Package-private entry point so tests can exercise chat capture deterministically. */
    boolean handleIncomingChatForTest(Player player, String text) {
        ActiveSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.expectsChat()) {
            return false;
        }
        String normalized = text == null ? "" : text.strip();
        bridge.runMainThread(() -> handleChatLine(player, session, normalized));
        return true;
    }

    ClickCallback<Audience> confirmationClickForTest(Player player) {
        ActiveSession session = Objects.requireNonNull(sessions.get(player.getUniqueId()));
        return audience -> dispatchConfirm(player, session, audience);
    }

    private void handleChatLine(Player player, ActiveSession session, String text) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        if (session.kind == SessionKind.CHAT_TEXT) {
            if (!consume(player.getUniqueId(), session)) {
                return;
            }
            if (isCancelWord(text)) {
                if (session.chatCancelMessage != null) {
                    player.sendMessage(session.chatCancelMessage);
                }
                safeRun(session.onCancel);
            } else {
                safeAccept(session.onTextSubmit, text);
            }
            safeRun(session.afterChatComplete);
            return;
        }

        if (session.kind == SessionKind.CHAT_CONFIRM) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (CONFIRM_WORDS.contains(lower)) {
                if (!consume(player.getUniqueId(), session)) {
                    return;
                }
                safeRun(session.onConfirm);
                safeRun(session.afterChatComplete);
            } else if (CANCEL_WORDS.contains(lower)) {
                if (!consume(player.getUniqueId(), session)) {
                    return;
                }
                safeRun(session.onCancel);
                safeRun(session.afterChatComplete);
            } else {
                player.sendMessage(Component.text("请点击聊天栏中的 [确认] / [取消]，或直接输入 确认 / 取消。", NamedTextColor.YELLOW));
            }
        }
    }

    private void startChatConfirm(Player player, ConfirmPrompt prompt) {
        ActiveSession session = registerSession(new ActiveSession(
                player.getUniqueId(),
                prompt.owner,
                SessionKind.CHAT_CONFIRM,
                null,
                prompt.onConfirm,
                prompt.onCancel,
                prompt.afterChatComplete,
                null,
                prompt.chatTimeoutMessage,
                prompt.executor));

        if (prompt.beforeChatFallback != null) {
            prompt.beforeChatFallback.run();
        }

        player.sendMessage(Component.text("── ", NamedTextColor.DARK_GRAY)
                .append(prompt.title.colorIfAbsent(NamedTextColor.GOLD))
                .append(Component.text(" ──", NamedTextColor.DARK_GRAY)));
        if (prompt.itemDescription != null) {
            player.sendMessage(prompt.itemDescription);
        }
        for (Component line : prompt.body) {
            player.sendMessage(line);
        }

        ClickCallback.Options options = callbackOptions(prompt.timeoutSeconds);
        Component confirmBtn = Component.text("[✔ ", NamedTextColor.GREEN)
                .append(prompt.confirmLabel)
                .append(Component.text("]", NamedTextColor.GREEN))
                .hoverEvent(HoverEvent.showText(Component.text("点击确认", NamedTextColor.GREEN)))
                .clickEvent(ClickEvent.callback(audience -> dispatchConfirm(player, session, audience), options));
        Component cancelBtn = Component.text("[✘ ", NamedTextColor.RED)
                .append(prompt.cancelLabel)
                .append(Component.text("]", NamedTextColor.RED))
                .hoverEvent(HoverEvent.showText(Component.text("点击取消", NamedTextColor.RED)))
                .clickEvent(ClickEvent.callback(audience -> dispatchCancel(player, session, audience), options));

        player.sendMessage(confirmBtn
                .append(Component.text("   "))
                .append(cancelBtn)
                .append(Component.text("  (或直接输入 确认 / 取消)", NamedTextColor.GRAY)));

        armTimeout(player, session, prompt.timeoutSeconds);
    }

    private Dialog buildTextDialog(Player player, ActiveSession session, TextPrompt prompt) {
        List<DialogBody> bodies = buildBody(player, prompt.artwork, prompt.item, prompt.itemDescription, prompt.body);
        DialogInput input = DialogInput.text(INPUT_KEY, prompt.label)
                .initial(prompt.initial)
                .maxLength(Math.max(1, prompt.maxLength))
                .width(Math.clamp(prompt.width, 1, 1024))
                .build();

        ClickCallback.Options options = callbackOptions(prompt.timeoutSeconds);
        ActionButton confirmBtn = ActionButton.builder(prompt.confirmLabel)
                .action(DialogAction.customClick((view, audience) -> executeForSession(player, session, audience,
                        () -> safeAccept(session.onTextSubmit, safeText(view.getText(INPUT_KEY)))), options))
                .build();

        ActionButton.Builder cancelBuilder = ActionButton.builder(prompt.cancelLabel);
        if (prompt.onCancel != null) {
            cancelBuilder.action(DialogAction.customClick(
                    (view, audience) -> dispatchCancel(player, session, audience), options));
        }

        DialogBase base = DialogBase.builder(prompt.title)
                .pause(false)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(bodies)
                .inputs(List.of(input))
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.confirmation(confirmBtn, cancelBuilder.build())));
    }

    private Dialog buildConfirmDialog(Player player, ActiveSession session, ConfirmPrompt prompt) {
        List<DialogBody> bodies = buildBody(player, prompt.artwork, prompt.item, prompt.itemDescription, prompt.body);
        ClickCallback.Options options = callbackOptions(prompt.timeoutSeconds);

        ActionButton confirmBtn = ActionButton.builder(prompt.confirmLabel)
                .action(DialogAction.customClick(
                        (view, audience) -> dispatchConfirm(player, session, audience), options))
                .build();

        ActionButton.Builder cancelBuilder = ActionButton.builder(prompt.cancelLabel);
        if (prompt.onCancel != null) {
            cancelBuilder.action(DialogAction.customClick(
                    (view, audience) -> dispatchCancel(player, session, audience), options));
        }

        DialogBase base = DialogBase.builder(prompt.title)
                .pause(false)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(bodies)
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.confirmation(confirmBtn, cancelBuilder.build())));
    }

    private List<DialogBody> buildBody(Player player, DialogArtwork artwork, ItemStack item, Component itemDescription,
                                       List<Component> lines) {
        List<DialogBody> bodies = new ArrayList<>();
        List<Component> information = new ArrayList<>();
        if (item != null && !item.getType().isAir()) {
            ItemStack copy = item.clone();
            if (copy.getAmount() < 1) {
                copy.setAmount(1);
            }
            ItemDialogBody.Builder itemBuilder = DialogBody.item(copy)
                    .showDecorations(true)
                    .showTooltip(true);
            if (itemDescription != null) {
                itemBuilder.description(DialogBody.plainMessage(itemDescription));
            }
            bodies.add(itemBuilder.build());
        } else if (itemDescription != null) information.add(itemDescription);
        for (Component line : lines) {
            if (line != null) {
                information.add(line);
            }
        }
        bodies.addAll(artworkBody(player, artwork, information));
        return bodies;
    }

    private void dispatchConfirm(Player player, ActiveSession session, Audience audience) {
        executeForSession(player, session, audience, () -> {
            safeRun(session.onConfirm);
            if (session.kind == SessionKind.CHAT_CONFIRM) {
                safeRun(session.afterChatComplete);
            }
        });
    }

    private void dispatchCancel(Player player, ActiveSession session, Audience audience) {
        executeForSession(player, session, audience, () -> {
            safeRun(session.onCancel);
            if (session.kind == SessionKind.CHAT_CONFIRM) {
                safeRun(session.afterChatComplete);
            }
        });
    }

    private void executeForSession(Player player, ActiveSession session, Audience audience, Runnable action) {
        if (!(audience instanceof Player clicker) || !session.playerId.equals(clicker.getUniqueId())) {
            return;
        }
        Runnable guarded = () -> {
            if (player.isOnline() && consume(player.getUniqueId(), session)) {
                action.run();
            }
        };
        if (session.executor != null) {
            session.executor.accept(guarded);
        } else {
            bridge.runMainThread(guarded);
        }
    }

    private ActiveSession registerSession(ActiveSession next) {
        ActiveSession previous = sessions.put(next.playerId, next);
        if (previous != null) {
            previous.invalidate();
        }
        return next;
    }

    private boolean consume(UUID playerId, ActiveSession session) {
        return sessions.remove(playerId, session) && session.tryConsume();
    }

    private void armTimeout(Player player, ActiveSession session, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return;
        }
        long ticks = Math.multiplyExact((long) timeoutSeconds, 20L);
        session.cancelTimeout = bridge.scheduleLater(() -> {
            if (!sessions.remove(session.playerId, session)) {
                return;
            }
            if (!session.tryConsume()) {
                return;
            }
            if (player.isOnline() && session.chatTimeoutMessage != null) {
                player.sendMessage(session.chatTimeoutMessage);
            }
        }, ticks);
    }

    private void safeRun(Runnable action) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            bridge.logWarning("Dialog 回调执行失败: " + e.getMessage(), e);
        }
    }

    private void safeAccept(Consumer<String> consumer, String value) {
        if (consumer == null) {
            return;
        }
        try {
            consumer.accept(value);
        } catch (RuntimeException | LinkageError e) {
            bridge.logWarning("Dialog 输入回调执行失败: " + e.getMessage(), e);
        }
    }

    private static ClickCallback.Options callbackOptions(int timeoutSeconds) {
        int lifetimeSeconds = timeoutSeconds > 0 ? Math.max(timeoutSeconds, 60) : 300;
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofSeconds(lifetimeSeconds))
                .build();
    }

    private static boolean isCancelWord(String text) {
        return text != null && (text.equalsIgnoreCase("cancel") || text.equals("取消"));
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static final class BukkitRuntimeBridge implements RuntimeBridge {
        private final Plugin plugin;
        private final DialogProtocolSupport protocolSupport;

        private BukkitRuntimeBridge(Plugin plugin, DialogProtocolSupport protocolSupport) {
            this.plugin = plugin;
            this.protocolSupport = protocolSupport;
        }

        @Override
        public int protocol(Player player) {
            return protocolSupport.protocol(player);
        }

        @Override
        public boolean prefersVanillaUi(Player player) {
            return DialogPrompts.isPreferVanillaUi(player);
        }

        @Override
        public void forgetProtocol(UUID playerId) {
            protocolSupport.forget(playerId);
        }

        @Override
        public void clearProtocols() {
            protocolSupport.clear();
        }

        @Override
        public void showDialog(Player player, Dialog dialog) {
            player.showDialog(dialog);
        }

        @Override
        public void runMainThread(Runnable task) {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, task);
        }

        @Override
        public Runnable scheduleLater(Runnable task, long delayTicks) {
            if (!plugin.isEnabled()) {
                return () -> { };
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return scheduled::cancel;
        }

        @Override
        public void logWarning(String message, Throwable error) {
            Logger logger = plugin.getLogger();
            if (error != null) {
                logger.log(Level.WARNING, message, error);
            } else {
                logger.warning(message);
            }
        }
    }

    public static final class TextPrompt {
        private final Object owner;
        private final DialogArtwork artwork;
        private final Component title;
        private final ItemStack item;
        private final Component itemDescription;
        private final List<Component> body;
        private final Component label;
        private final String initial;
        private final int maxLength;
        private final int width;
        private final Component confirmLabel;
        private final Component cancelLabel;
        private final int timeoutSeconds;
        private final Component chatPrompt;
        private final Component chatCancelMessage;
        private final Component chatTimeoutMessage;
        private final Consumer<Runnable> executor;
        private final Runnable beforeChatFallback;
        private final Runnable afterChatComplete;
        private final Consumer<String> onSubmit;
        private final Runnable onCancel;

        private TextPrompt(Builder builder) {
            this.owner = builder.owner;
            this.artwork = builder.artwork;
            this.title = Objects.requireNonNull(builder.title, "title");
            this.item = builder.item;
            this.itemDescription = builder.itemDescription;
            this.body = List.copyOf(builder.body);
            this.label = Objects.requireNonNull(builder.label, "label");
            this.initial = builder.initial == null ? "" : builder.initial;
            this.maxLength = Math.max(1, builder.maxLength);
            this.width = builder.width;
            this.confirmLabel = builder.confirmLabel;
            this.cancelLabel = builder.cancelLabel;
            this.timeoutSeconds = builder.timeoutSeconds;
            this.chatPrompt = builder.chatPrompt;
            this.chatCancelMessage = builder.chatCancelMessage;
            this.chatTimeoutMessage = builder.chatTimeoutMessage;
            this.executor = builder.executor;
            this.beforeChatFallback = builder.beforeChatFallback;
            this.afterChatComplete = builder.afterChatComplete;
            this.onSubmit = Objects.requireNonNull(builder.onSubmit, "onSubmit");
            this.onCancel = builder.onCancel;
        }

        public static Builder builder(Component title, Component label, Consumer<String> onSubmit) {
            return new Builder(title, label, onSubmit);
        }

        public static final class Builder {
            private Object owner;
            private DialogArtwork artwork;
            private final Component title;
            private ItemStack item;
            private Component itemDescription;
            private List<Component> body = List.of();
            private final Component label;
            private String initial = "";
            private int maxLength = 64;
            private int width = DEFAULT_INPUT_WIDTH;
            private Component confirmLabel = Component.text("确定", NamedTextColor.GREEN);
            private Component cancelLabel = Component.text("取消", NamedTextColor.RED);
            private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            private Component chatPrompt;
            private Component chatCancelMessage = Component.text("已取消输入。", NamedTextColor.GRAY);
            private Component chatTimeoutMessage = Component.text("输入已超时。", NamedTextColor.GRAY);
            private Consumer<Runnable> executor;
            private Runnable beforeChatFallback;
            private Runnable afterChatComplete;
            private final Consumer<String> onSubmit;
            private Runnable onCancel;

            private Builder(Component title, Component label, Consumer<String> onSubmit) {
                this.title = title;
                this.label = label;
                this.onSubmit = onSubmit;
            }

            public Builder owner(Object owner) {
                this.owner = owner;
                return this;
            }

            public Builder artwork(DialogArtwork artwork) {
                this.artwork = artwork;
                return this;
            }

            public Builder item(ItemStack item) {
                this.item = item;
                return this;
            }

            public Builder itemDescription(Component itemDescription) {
                this.itemDescription = itemDescription;
                return this;
            }

            public Builder body(List<Component> body) {
                this.body = body == null ? List.of() : body;
                return this;
            }

            public Builder initial(String initial) {
                this.initial = initial == null ? "" : initial;
                return this;
            }

            public Builder maxLength(int maxLength) {
                this.maxLength = maxLength;
                return this;
            }

            public Builder width(int width) {
                this.width = width;
                return this;
            }

            public Builder confirmLabel(Component confirmLabel) {
                this.confirmLabel = Objects.requireNonNull(confirmLabel, "confirmLabel");
                return this;
            }

            public Builder cancelLabel(Component cancelLabel) {
                this.cancelLabel = Objects.requireNonNull(cancelLabel, "cancelLabel");
                return this;
            }

            public Builder timeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
                return this;
            }

            public Builder chatPrompt(Component chatPrompt) {
                this.chatPrompt = chatPrompt;
                return this;
            }

            public Builder chatCancelMessage(Component chatCancelMessage) {
                this.chatCancelMessage = chatCancelMessage;
                return this;
            }

            public Builder chatTimeoutMessage(Component chatTimeoutMessage) {
                this.chatTimeoutMessage = chatTimeoutMessage;
                return this;
            }

            public Builder executor(Consumer<Runnable> executor) {
                this.executor = executor;
                return this;
            }

            public Builder beforeChatFallback(Runnable beforeChatFallback) {
                this.beforeChatFallback = beforeChatFallback;
                return this;
            }

            public Builder afterChatComplete(Runnable afterChatComplete) {
                this.afterChatComplete = afterChatComplete;
                return this;
            }

            public Builder onCancel(Runnable onCancel) {
                this.onCancel = onCancel;
                return this;
            }

            public TextPrompt build() {
                return new TextPrompt(this);
            }
        }
    }

    public static final class ConfirmPrompt {
        private final Object owner;
        private final DialogArtwork artwork;
        private final Component title;
        private final ItemStack item;
        private final Component itemDescription;
        private final List<Component> body;
        private final Component confirmLabel;
        private final Component cancelLabel;
        private final int timeoutSeconds;
        private final Component chatTimeoutMessage;
        private final Consumer<Runnable> executor;
        private final Runnable beforeChatFallback;
        private final Runnable afterChatComplete;
        private final Runnable onConfirm;
        private final Runnable onCancel;

        private ConfirmPrompt(Builder builder) {
            this.owner = builder.owner;
            this.artwork = builder.artwork;
            this.title = Objects.requireNonNull(builder.title, "title");
            this.item = builder.item;
            this.itemDescription = builder.itemDescription;
            this.body = List.copyOf(builder.body);
            this.confirmLabel = builder.confirmLabel;
            this.cancelLabel = builder.cancelLabel;
            this.timeoutSeconds = builder.timeoutSeconds;
            this.chatTimeoutMessage = builder.chatTimeoutMessage;
            this.executor = builder.executor;
            this.beforeChatFallback = builder.beforeChatFallback;
            this.afterChatComplete = builder.afterChatComplete;
            this.onConfirm = Objects.requireNonNull(builder.onConfirm, "onConfirm");
            this.onCancel = builder.onCancel;
        }

        public static Builder builder(Component title, Runnable onConfirm) {
            return new Builder(title, onConfirm);
        }

        public static final class Builder {
            private Object owner;
            private DialogArtwork artwork;
            private final Component title;
            private ItemStack item;
            private Component itemDescription;
            private List<Component> body = List.of();
            private Component confirmLabel = Component.text("确认", NamedTextColor.GREEN);
            private Component cancelLabel = Component.text("取消", NamedTextColor.RED);
            private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            private Component chatTimeoutMessage = Component.text("确认操作已超时。", NamedTextColor.GRAY);
            private Consumer<Runnable> executor;
            private Runnable beforeChatFallback;
            private Runnable afterChatComplete;
            private final Runnable onConfirm;
            private Runnable onCancel;

            private Builder(Component title, Runnable onConfirm) {
                this.title = title;
                this.onConfirm = onConfirm;
            }

            public Builder owner(Object owner) {
                this.owner = owner;
                return this;
            }

            public Builder artwork(DialogArtwork artwork) {
                this.artwork = artwork;
                return this;
            }

            public Builder item(ItemStack item) {
                this.item = item;
                return this;
            }

            public Builder itemDescription(Component itemDescription) {
                this.itemDescription = itemDescription;
                return this;
            }

            public Builder body(List<Component> body) {
                this.body = body == null ? List.of() : body;
                return this;
            }

            public Builder confirmLabel(Component confirmLabel) {
                this.confirmLabel = Objects.requireNonNull(confirmLabel, "confirmLabel");
                return this;
            }

            public Builder cancelLabel(Component cancelLabel) {
                this.cancelLabel = Objects.requireNonNull(cancelLabel, "cancelLabel");
                return this;
            }

            public Builder timeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
                return this;
            }

            public Builder chatTimeoutMessage(Component chatTimeoutMessage) {
                this.chatTimeoutMessage = chatTimeoutMessage;
                return this;
            }

            public Builder executor(Consumer<Runnable> executor) {
                this.executor = executor;
                return this;
            }

            public Builder beforeChatFallback(Runnable beforeChatFallback) {
                this.beforeChatFallback = beforeChatFallback;
                return this;
            }

            public Builder afterChatComplete(Runnable afterChatComplete) {
                this.afterChatComplete = afterChatComplete;
                return this;
            }

            public Builder onCancel(Runnable onCancel) {
                this.onCancel = onCancel;
                return this;
            }

            public ConfirmPrompt build() {
                return new ConfirmPrompt(this);
            }
        }
    }
}
