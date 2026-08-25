package dev.matt.ptime;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

public final class PtimeScreen extends Screen {
    private static final int BACKGROUND = 0xD0141722;
    private static final int CARD = 0xE6222734;
    private static final int CARD_ACCENT = 0xFF6EE7B7;
    private static final int TEXT = 0xFFF3F4F6;
    private static final int SUBTEXT = 0xFF9CA3AF;
    private static final int PLAYER_TIME = 0xFFFDE68A;
    private static final int LOCAL_TIME = 0xFF93C5FD;
    private static final int TITLE = 0xFFB8F2DE;
    private static final int FAVORITE = 0xFFFDE68A;
    private static final int UNFAVORITE = 0xFFCBD5E1;
    private static final int LIST_LEFT = 18;
    private static final int LIST_RIGHT_MARGIN = 18;
    private static final int LIST_TOP = 84;
    private static final int LIST_BOTTOM_MARGIN = 18;
    private static final int SECTION_HEADER_HEIGHT = 18;
    private static final int SECTION_GAP = 10;
    private static final int SCROLL_STEP = 20;
    private static final int REGULAR_CARD_MIN_WIDTH = 260;
    private static final int REGULAR_CARD_MAX_WIDTH = 320;
    private static final int COMPACT_CARD_MIN_WIDTH = 200;
    private static final int COMPACT_CARD_MAX_WIDTH = 260;
    private static final int FACE_SIZE = 32;
    private static LayoutMode rememberedLayoutMode = LayoutMode.REGULAR;

    private final PtimeManager manager;
    private final List<CardHitbox> cardHitboxes = new ArrayList<>();
    private LayoutMode layoutMode = rememberedLayoutMode;
    private EditBox searchField;
    private String searchQuery = "";
    private int scrollOffset;
    private int maxScrollOffset;

    public PtimeScreen(PtimeManager manager) {
        super(Component.literal("ptime"));
        this.manager = manager;
    }

    @Override
    protected void init() {
        int searchWidth = Math.max(120, width - 228);
        searchField = new EditBox(font, LIST_LEFT, 32, searchWidth, 20, Component.literal("Search players"));
        searchField.setMaxLength(64);
        searchField.setValue(searchQuery);
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        int layoutWidth = 96;
        int closeWidth = 80;
        int layoutX = width - LIST_RIGHT_MARGIN - closeWidth - 8 - layoutWidth;
        int closeX = width - LIST_RIGHT_MARGIN - closeWidth;

        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
            .bounds(closeX, 32, closeWidth, 20)
            .build());
        addRenderableWidget(Button.builder(layoutButtonText(), button -> {
            layoutMode = layoutMode == LayoutMode.REGULAR ? LayoutMode.COMPACT : LayoutMode.REGULAR;
            rememberedLayoutMode = layoutMode;
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(layoutX, 32, layoutWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float deltaTicks) {
        extractor.fill(0, 0, width, height, BACKGROUND);

        extractor.text(font, titleText(), LIST_LEFT, 12, TITLE, true);
        extractor.text(
            font,
            Component.literal("Search names, scroll for more cards, and click the star to favorite one."),
            LIST_LEFT,
            58,
            SUBTEXT,
            true
        );

        if (searchField != null && !searchQuery.equals(searchField.getValue())) {
            searchQuery = searchField.getValue();
            scrollOffset = 0;
        }

        List<PtimeManager.TrackedPlayer> players = manager.trackedPlayers();
        List<PtimeManager.TrackedPlayer> filteredPlayers = players.stream()
            .filter(player -> matchesQuery(player.name(), searchQuery))
            .sorted(playerComparator(searchQuery))
            .toList();

        List<PtimeManager.TrackedPlayer> favorites = filteredPlayers.stream()
            .filter(player -> manager.isFavorite(player.name()))
            .toList();
        List<PtimeManager.TrackedPlayer> others = filteredPlayers.stream()
            .filter(player -> !manager.isFavorite(player.name()))
            .toList();

        LayoutMetrics metrics = layoutMetrics();
        int viewportTop = LIST_TOP;
        int viewportBottom = height - LIST_BOTTOM_MARGIN;
        int viewportHeight = Math.max(0, viewportBottom - viewportTop);
        int contentHeight = computeContentHeight(favorites, others, metrics);
        maxScrollOffset = Math.max(0, contentHeight - viewportHeight);
        scrollOffset = clamp(scrollOffset, 0, maxScrollOffset);

        cardHitboxes.clear();
        extractor.enableScissor(LIST_LEFT, viewportTop, width - LIST_RIGHT_MARGIN, viewportBottom);

        int y = viewportTop - scrollOffset;
        if (filteredPlayers.isEmpty()) {
            y = drawEmptyState(extractor, y, viewportBottom);
        } else {
            if (!favorites.isEmpty()) {
                y = drawSection(extractor, "Favorites", favorites, y, metrics, mouseX, mouseY);
            }
            if (!others.isEmpty()) {
                y = drawSection(extractor, favorites.isEmpty() ? null : "All players", others, y, metrics, mouseX, mouseY);
            }
        }

        extractor.disableScissor();
        super.extractRenderState(extractor, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
        double mouseX = event.x();
        double mouseY = event.y();

        if (mouseX >= LIST_LEFT && mouseX <= width - LIST_RIGHT_MARGIN
            && mouseY >= LIST_TOP && mouseY <= height - LIST_BOTTOM_MARGIN) {
            for (CardHitbox hitbox : cardHitboxes) {
                if (hitbox.contains(mouseX, mouseY)) {
                    manager.toggleFavorite(hitbox.playerName());
                    return true;
                }
            }
        }

        return super.mouseClicked(event, handled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScrollOffset > 0
            && mouseX >= LIST_LEFT
            && mouseX <= width - LIST_RIGHT_MARGIN
            && mouseY >= LIST_TOP
            && mouseY <= height - LIST_BOTTOM_MARGIN
        ) {
            scrollOffset = clamp((int) Math.round(scrollOffset - (verticalAmount * SCROLL_STEP)), 0, maxScrollOffset);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int drawEmptyState(GuiGraphicsExtractor extractor, int y, int viewportBottom) {
        String message = searchQuery.isBlank()
            ? "No tracked players yet."
            : "No players match \"" + searchQuery + "\".";
        extractor.text(font, Component.literal(message), LIST_LEFT, y + 8, TEXT, true);
        extractor.text(
            font,
            searchQuery.isBlank()
                ? Component.literal("Example: /ptime Steve America/New_York")
                : Component.literal("Clear the search to see every tracked player."),
            LIST_LEFT,
            y + 24,
            SUBTEXT,
            true
        );
        return viewportBottom;
    }

    private int drawSection(
        GuiGraphicsExtractor extractor,
        String label,
        List<PtimeManager.TrackedPlayer> players,
        int y,
        LayoutMetrics metrics,
        int mouseX,
        int mouseY
    ) {
        if (label != null) {
            y = drawSectionHeader(extractor, label, y);
        }

        int rows = (players.size() + metrics.columns - 1) / metrics.columns;
        ZonedDateTime clientTime = ZonedDateTime.now(manager.selfZoneId());
        for (int index = 0; index < players.size(); index++) {
            int column = index % metrics.columns;
            int row = index / metrics.columns;
            int x = metrics.left + column * (metrics.cardWidth + metrics.gap);
            int cardY = y + row * (metrics.cardHeight + metrics.gap);

            drawCard(extractor, players.get(index), x, cardY, metrics.cardWidth, metrics.cardHeight, clientTime, mouseX, mouseY);
        }

        int sectionHeight = rows * metrics.cardHeight + Math.max(0, rows - 1) * metrics.gap;
        y += sectionHeight;
        if (!players.isEmpty()) {
            y += SECTION_GAP;
        }
        return y;
    }

    private int drawSectionHeader(GuiGraphicsExtractor extractor, String label, int y) {
        extractor.text(font, Component.literal(label), LIST_LEFT, y, TITLE, true);
        int lineX = LIST_LEFT + font.width(label) + 8;
        int lineRight = Math.max(lineX, width - LIST_RIGHT_MARGIN);
        extractor.fill(lineX, y + 7, lineRight, y + 8, 0x406B7280);
        return y + SECTION_HEADER_HEIGHT;
    }

    private void drawCard(
        GuiGraphicsExtractor extractor,
        PtimeManager.TrackedPlayer player,
        int x,
        int y,
        int width,
        int height,
        ZonedDateTime clientTime,
        int mouseX,
        int mouseY
    ) {
        ZonedDateTime playerTime = ZonedDateTime.now(player.zoneId());
        boolean sameTimezone = player.zoneId().equals(manager.selfZoneId());
        boolean favorite = manager.isFavorite(player.name());
        boolean regular = layoutMode == LayoutMode.REGULAR;
        
        int textX = x + 56;
        int rightX = x + width - 22;

        extractor.fill(x, y, x + width, y + height, CARD);
        extractor.fill(x, y, x + width, y + 2, CARD_ACCENT);
        extractor.outline(x, y, width, height, 0x804B5563);

        drawSkin(extractor, player, x + 12, y + 6);

        extractor.text(font, Component.literal(player.name()), textX, y + 8, TEXT, true);
        extractor.text(
            font,
            Component.literal(PtimeManager.timezoneLabel(player.timezoneId()) + "  " + PtimeManager.formatOffset(playerTime)),
            textX,
            y + (regular ? 24 : 22),
            SUBTEXT,
            true
        );

        String playerClock = PtimeManager.formatTime(playerTime);
        extractor.text(font, Component.literal(playerClock), textX, y + (regular ? 42 : 38), PLAYER_TIME, true);

        if (sameTimezone) {
            extractor.text(font, Component.literal("Same timezone"), textX, y + (regular ? 58 : 52), SUBTEXT, true);
        } else {
            String yourClock = PtimeManager.formatTime(clientTime);
            extractor.text(
                font,
                Component.literal(yourClock),
                rightX - font.width(yourClock),
                y + (regular ? 42 : 38),
                LOCAL_TIME,
                true
            );
            extractor.text(font, Component.literal(PtimeManager.formatOffset(playerTime)), textX, y + (regular ? 58 : 52), SUBTEXT, true);
        }

        drawFavoriteToggle(extractor, favorite, x + width - 16, y + 4, mouseX, mouseY);
        cardHitboxes.add(new CardHitbox(player.name(), x + width - 18, y + 2, 14, 14));
    }

    private void drawFavoriteToggle(GuiGraphicsExtractor extractor, boolean favorite, int x, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x - 2 && mouseX <= x + 12 && mouseY >= y - 2 && mouseY <= y + 12;
        int color = favorite ? FAVORITE : UNFAVORITE;
        if (hovered) {
            color = favorite ? 0xFFFFF3A3 : 0xFFFFFFFF;
        }

        extractor.centeredText(font, Component.literal(favorite ? "\u2605" : "\u2606"), x + 5, y + 1, color);
    }

    private void drawSkin(GuiGraphicsExtractor extractor, PtimeManager.TrackedPlayer player, int x, int y) {
        Identifier texturePath = resolveSkinTexture(player.name());
        
        // Base layer
        extractor.blit(RenderPipelines.GUI_TEXTURED, texturePath, x, y, 8.0f, 8.0f, FACE_SIZE, FACE_SIZE, 8, 8, 64, 64, 0xFFFFFFFF);
        
        // Top layer (hat) with a tad of inflation (1px)
        int i = 1;
        extractor.blit(RenderPipelines.GUI_TEXTURED, texturePath, x - i, y - i, 40.0f, 8.0f, FACE_SIZE + 2 * i, FACE_SIZE + 2 * i, 8, 8, 64, 64, 0xFFFFFFFF);
    }

    private Identifier resolveSkinTexture(String playerName) {
        Optional<PtimeManager.SkinRenderData> skin = manager.getSkin(playerName);
        if (skin.isPresent()) {
            PtimeManager.SkinRenderData data = skin.get();
            if (data.downloadedTexture() != null) {
                return data.downloadedTexture();
            }
            if (data.onlineSkin() instanceof PlayerSkin onlineSkin) {
                return onlineSkin.body().texturePath();
            }
        }
        return DefaultPlayerSkin.getDefaultTexture();
    }

    private int computeContentHeight(List<PtimeManager.TrackedPlayer> favorites, List<PtimeManager.TrackedPlayer> others, LayoutMetrics metrics) {
        int height = 0;
        if (!favorites.isEmpty()) {
            height += SECTION_HEADER_HEIGHT;
            height += sectionBodyHeight(favorites.size(), metrics);
            height += SECTION_GAP;
        }
        if (!others.isEmpty()) {
            if (!favorites.isEmpty()) {
                height += SECTION_HEADER_HEIGHT;
            }
            height += sectionBodyHeight(others.size(), metrics);
            height += SECTION_GAP;
        }
        if (height == 0) {
            height = 40;
        }
        return height;
    }

    private int sectionBodyHeight(int playerCount, LayoutMetrics metrics) {
        int rows = (playerCount + metrics.columns - 1) / metrics.columns;
        return rows * metrics.cardHeight + Math.max(0, rows - 1) * metrics.gap;
    }

    private LayoutMetrics layoutMetrics() {
        int availableWidth = width - (LIST_LEFT + LIST_RIGHT_MARGIN);
        int gap = layoutMode == LayoutMode.REGULAR ? 8 : 6;
        int minCardWidth = layoutMode == LayoutMode.REGULAR ? REGULAR_CARD_MIN_WIDTH : COMPACT_CARD_MIN_WIDTH;
        int maxCardWidth = layoutMode == LayoutMode.REGULAR ? REGULAR_CARD_MAX_WIDTH : COMPACT_CARD_MAX_WIDTH;
        int columns = Math.max(1, (availableWidth + gap) / (minCardWidth + gap));
        int cardWidth = Math.min(maxCardWidth, Math.max(minCardWidth, (availableWidth - ((columns - 1) * gap)) / columns));
        int gridWidth = columns * cardWidth + (columns - 1) * gap;
        int left = LIST_LEFT + Math.max(0, (availableWidth - gridWidth) / 2);
        int cardHeight = layoutMode == LayoutMode.REGULAR ? 72 : 64;
        return new LayoutMetrics(left, columns, cardWidth, cardHeight, gap);
    }

    private Comparator<PtimeManager.TrackedPlayer> playerComparator(String query) {
        if (query == null || query.isBlank()) {
            return Comparator.comparing(PtimeManager.TrackedPlayer::name, String.CASE_INSENSITIVE_ORDER);
        }

        return Comparator
            .comparingInt((PtimeManager.TrackedPlayer player) -> searchScore(player.name(), query))
            .thenComparing(PtimeManager.TrackedPlayer::name, String.CASE_INSENSITIVE_ORDER);
    }

    private boolean matchesQuery(String playerName, String query) {
        return searchScore(playerName, query) != Integer.MAX_VALUE;
    }

    private int searchScore(String playerName, String query) {
        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return 0;
        }

        String normalizedName = playerName.toLowerCase(Locale.ROOT);
        int directIndex = normalizedName.indexOf(trimmed);
        if (directIndex >= 0) {
            return directIndex;
        }

        int cursor = 0;
        int score = 100;
        for (int i = 0; i < trimmed.length(); i++) {
            int found = normalizedName.indexOf(trimmed.charAt(i), cursor);
            if (found < 0) {
                return Integer.MAX_VALUE;
            }
            score += found - cursor;
            cursor = found + 1;
        }

        return score + Math.max(0, normalizedName.length() - trimmed.length());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private Component titleText() {
        return Component.literal("P-Time!").withStyle(ChatFormatting.BOLD);
    }

    private Component layoutButtonText() {
        return Component.literal(layoutMode == LayoutMode.REGULAR ? "Compact" : "Regular");
    }

    private enum LayoutMode {
        REGULAR,
        COMPACT
    }

    private record LayoutMetrics(int left, int columns, int cardWidth, int cardHeight, int gap) {
    }

    private record CardHitbox(String playerName, int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }
}
