package dev.prime.render.vulkan;

import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Deterministic mip-aligned rectangle packing for one translated material channel. */
final class TexturePageLayout {
    static final int MAX_PAGE_COUNT = ShaderAbi.MATERIAL_PAGE_COUNT;
    private static final int TARGET_PAGE_EXTENT = 2048;
    private static final int BASE_COLOR_TARGET_PAGE_EXTENT = 4096;
    private static final int MIN_PAGE_EXTENT = 64;

    private TexturePageLayout() {
    }

    static Layout pack(
            List<LabPbrAtlasFrame.Sprite> sprites,
            Function<LabPbrAtlasFrame.Sprite, ? extends LabPbrAtlasFrame.TextureSource> source,
            int requestedMipLevels) {
        return pack(
                sprites,
                source,
                requestedMipLevels,
                TARGET_PAGE_EXTENT,
                MAX_PAGE_COUNT);
    }

    static Layout packBaseColor(
            List<LabPbrAtlasFrame.Sprite> sprites, int requestedMipLevels) {
        return pack(
                sprites,
                LabPbrAtlasFrame.Sprite::baseColor,
                requestedMipLevels,
                BASE_COLOR_TARGET_PAGE_EXTENT,
                ShaderAbi.BASE_COLOR_PAGE_COUNT);
    }

    private static Layout pack(
            List<LabPbrAtlasFrame.Sprite> sprites,
            Function<LabPbrAtlasFrame.Sprite, ? extends LabPbrAtlasFrame.TextureSource> source,
            int requestedMipLevels,
            int targetPageExtent,
            int maximumPageCount) {
        ArrayList<Request> regular = new ArrayList<>();
        ArrayList<Request> oversized = new ArrayList<>();
        long totalArea = 0L;
        int maximumRegularExtent = 1;
        for (LabPbrAtlasFrame.Sprite sprite : sprites) {
            if (source.apply(sprite) == null) {
                continue;
            }
            int outerWidth = Math.addExact(sprite.contentWidth(), 2 * sprite.padding());
            int outerHeight = Math.addExact(sprite.contentHeight(), 2 * sprite.padding());
            int spriteMipLevels = Math.min(
                    requestedMipLevels,
                    32 - Integer.numberOfLeadingZeros(Math.max(outerWidth, outerHeight)));
            int alignment = 1 << Math.max(0, spriteMipLevels - 1);
            Request request = new Request(
                    sprite,
                    alignUp(outerWidth, alignment),
                    alignUp(outerHeight, alignment),
                    alignment);
            if (request.width > targetPageExtent || request.height > targetPageExtent) {
                oversized.add(request);
            } else {
                regular.add(request);
                totalArea = Math.addExact(totalArea, (long) request.width * request.height);
                maximumRegularExtent = Math.max(
                        maximumRegularExtent, Math.max(request.width, request.height));
            }
        }
        regular.sort(Comparator.comparingInt(Request::height)
                .thenComparingInt(Request::width)
                .thenComparingInt(request -> request.sprite.textureId())
                .reversed());
        oversized.sort(Comparator.comparingInt(request -> request.sprite.textureId()));

        int estimatedExtent = nextPowerOfTwo((int) Math.ceil(Math.sqrt(totalArea)));
        int regularExtent = Math.min(
                targetPageExtent,
                Math.max(MIN_PAGE_EXTENT, Math.max(maximumRegularExtent, estimatedExtent)));
        ArrayList<PageBuilder> pages = new ArrayList<>();
        HashMap<Integer, Placement> placements = new HashMap<>();
        for (Request request : regular) {
            Point point = null;
            int pageIndex = -1;
            for (int index = 0; index < pages.size(); index++) {
                PageBuilder candidate = pages.get(index);
                if (candidate.width != regularExtent || candidate.height != regularExtent) {
                    continue;
                }
                point = candidate.tryPlace(request.width, request.height, request.alignment);
                if (point != null) {
                    pageIndex = index;
                    break;
                }
            }
            if (point == null) {
                requirePageCapacity(pages.size() + 1, maximumPageCount);
                PageBuilder page = new PageBuilder(regularExtent, regularExtent);
                pages.add(page);
                pageIndex = pages.size() - 1;
                point = page.tryPlace(request.width, request.height, request.alignment);
                if (point == null) {
                    throw new IllegalStateException("Texture rectangle does not fit its selected page");
                }
            }
            placements.put(
                    request.sprite.textureId(),
                    new Placement(pageIndex, point.x, point.y, request.sprite));
        }
        for (Request request : oversized) {
            requirePageCapacity(pages.size() + 1, maximumPageCount);
            int pageIndex = pages.size();
            PageBuilder page = new PageBuilder(
                    nextPowerOfTwo(request.width),
                    nextPowerOfTwo(request.height));
            pages.add(page);
            Point point = page.tryPlace(request.width, request.height, request.alignment);
            if (point == null) {
                throw new IllegalStateException("Oversized texture rectangle does not fit its page");
            }
            placements.put(
                    request.sprite.textureId(),
                    new Placement(pageIndex, point.x, point.y, request.sprite));
        }
        if (pages.isEmpty()) {
            pages.add(new PageBuilder(1, 1));
        }
        ArrayList<Page> immutablePages = new ArrayList<>(pages.size());
        for (PageBuilder page : pages) {
            immutablePages.add(new Page(
                    page.width,
                    Math.min(page.height, nextPowerOfTwo(Math.max(1, page.usedHeight())))));
        }
        return new Layout(List.copyOf(immutablePages), Map.copyOf(placements));
    }

    private static void requirePageCapacity(int pageCount, int maximumPageCount) {
        if (pageCount > maximumPageCount) {
            throw new IllegalStateException(
                    "Translated material channel exceeds the "
                            + maximumPageCount + " page ABI");
        }
    }

    private static int alignUp(int value, int alignment) {
        return Math.addExact(value, alignment - 1) / alignment * alignment;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value > 1 << 30) {
            throw new IllegalArgumentException("Texture page extent overflows 32-bit coordinates");
        }
        return Integer.highestOneBit(value - 1) << 1;
    }

    record Layout(List<Page> pages, Map<Integer, Placement> placements) {
        Placement placement(int textureId) {
            return this.placements.get(textureId);
        }
    }

    record Page(int width, int height) {
    }

    record Placement(
            int page,
            int outerX,
            int outerY,
            LabPbrAtlasFrame.Sprite sprite) {
        int contentX() {
            return this.outerX + this.sprite.padding();
        }

        int contentY() {
            return this.outerY + this.sprite.padding();
        }

        int mipX(int mip) {
            return this.outerX >> mip;
        }

        int mipY(int mip) {
            return this.outerY >> mip;
        }
    }

    private record Request(
            LabPbrAtlasFrame.Sprite sprite,
            int width,
            int height,
            int alignment) {
    }

    private record Point(int x, int y) {
    }

    private static final class PageBuilder {
        private final int width;
        private final int height;
        private int x;
        private int y;
        private int rowHeight;

        private PageBuilder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private Point tryPlace(int width, int height, int alignment) {
            if (width > this.width || height > this.height) {
                return null;
            }
            int alignedX = alignUp(this.x, alignment);
            int alignedY = this.y;
            boolean newRow = false;
            if (alignedX + width > this.width) {
                alignedX = 0;
                alignedY = alignUp(this.y + this.rowHeight, alignment);
                newRow = true;
            }
            if (alignedY + height > this.height) {
                return null;
            }
            this.x = alignedX + width;
            this.y = alignedY;
            this.rowHeight = newRow ? height : Math.max(this.rowHeight, height);
            return new Point(alignedX, alignedY);
        }

        private int usedHeight() {
            return this.y + this.rowHeight;
        }
    }
}
