#ifndef PRIME_MC_H
#define PRIME_MC_H
#include <stdint.h>

#define PRIME_MC_SOURCE_VERSION 7
#define PRIME_MC_RESOURCE_REPLACE 1

typedef struct PrimeMcIdentity {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t source_version;
    uint32_t game_version;
    uint64_t resource_generation;
    uint64_t epoch;
    uint64_t batch;
} PrimeMcIdentity;

typedef struct PrimeMcRange {
    uint64_t offset;
    uint64_t count;
} PrimeMcRange;

typedef struct PrimeMcEvent {
    uint32_t kind;
    int32_t x;
    int32_t y;
    int32_t z;
} PrimeMcEvent;

typedef struct PrimeMcPlan {
    PrimeMcIdentity identity;
    double position[2];
    int32_t radius;
    int32_t min_y;
    int32_t max_y;
    int32_t source[4];
    uint32_t reserved;
    uint64_t tick;
    const PrimeMcEvent *events;
    uint64_t event_count;
} PrimeMcPlan;

typedef struct PrimeMcPlacement {
    uint32_t offset;
    float horizontal;
    float vertical;
    uint32_t seed;
} PrimeMcPlacement;

typedef struct PrimeMcState {
    uint32_t id;
    uint32_t flags;
    uint32_t model;
    uint32_t faces[6];
    uint32_t support;
    uint32_t fluid_level;
    uint32_t fluid_falling;
    uint32_t fluid_material;
    uint32_t emission;
    PrimeMcPlacement placement;
    PrimeMcRange name;
    PrimeMcRange fluid_name;
} PrimeMcState;

typedef struct PrimeMcQuad {
    uint32_t face;
    int32_t tint;
    uint32_t layer;
    uint32_t sprite;
    uint32_t emission;
    uint32_t reserved;
    float positions[12];
    uint64_t uv_pairs[4];
} PrimeMcQuad;

typedef struct PrimeMcModelChild {
    uint32_t weight;
    uint32_t model;
} PrimeMcModelChild;

typedef struct PrimeMcModel {
    uint32_t id;
    uint32_t kind;
    uint32_t alias;
    uint32_t reserved;
    PrimeMcRange quads;
    PrimeMcRange children;
} PrimeMcModel;

typedef struct PrimeMcFace {
    uint32_t id;
    uint32_t reserved;
    PrimeMcRange u;
    PrimeMcRange v;
    PrimeMcRange words;
} PrimeMcFace;

typedef struct PrimeMcFluid {
    uint32_t id;
    uint32_t layer;
    uint32_t flags;
    uint32_t identities[3];
    float bounds[12];
} PrimeMcFluid;

typedef struct PrimeMcImage {
    uint32_t width;
    uint32_t height;
    PrimeMcRange pixels;
} PrimeMcImage;

typedef struct PrimeMcAnimationFrame {
    uint32_t frame;
    uint32_t duration;
} PrimeMcAnimationFrame;

typedef struct PrimeMcSprite {
    uint32_t id;
    uint32_t interpolate;
    float bounds[4];
    uint32_t extent[2];
    PrimeMcRange name;
    PrimeMcRange images;
    PrimeMcRange frames;
    uint32_t normal_image;
    uint32_t specular_image;
} PrimeMcSprite;

typedef struct PrimeMcResourceBatch {
    PrimeMcIdentity identity;
    uint32_t flags;
    uint32_t reserved;
    uint64_t tick;
    PrimeMcImage atlas;
    const PrimeMcState *states;
    uint64_t state_count;
    const PrimeMcModel *models;
    uint64_t model_count;
    const PrimeMcQuad *quads;
    uint64_t quad_count;
    const PrimeMcModelChild *children;
    uint64_t child_count;
    const PrimeMcFace *faces;
    uint64_t face_count;
    const PrimeMcFluid *fluids;
    uint64_t fluid_count;
    const PrimeMcSprite *sprites;
    uint64_t sprite_count;
    const PrimeMcImage *images;
    uint64_t image_count;
    const PrimeMcAnimationFrame *frames;
    uint64_t frame_count;
    const double *coordinates;
    uint64_t coordinate_count;
    const uint64_t *words;
    uint64_t word_count;
    const uint8_t *bytes;
    uint64_t byte_count;
} PrimeMcResourceBatch;

typedef struct PrimeMcSection {
    int32_t x;
    int32_t y;
    int32_t z;
    uint32_t present;
    uint32_t bits;
    uint32_t reserved;
    PrimeMcRange palette;
    PrimeMcRange storage;
} PrimeMcSection;

typedef struct PrimeMcSectionBatch {
    PrimeMcIdentity identity;
    const PrimeMcSection *sections;
    uint64_t section_count;
    const uint32_t *palette;
    uint64_t palette_count;
    const uint64_t *words;
    uint64_t word_count;
} PrimeMcSectionBatch;

typedef struct PrimeMcSectionRequest {
    int32_t x;
    int32_t y;
    int32_t z;
    uint32_t active;
} PrimeMcSectionRequest;

typedef struct PrimeMcColumnRequest {
    int32_t x;
    int32_t z;
    uint32_t active;
} PrimeMcColumnRequest;

typedef struct PrimeMcColorRequest {
    int32_t x;
    int32_t y;
    int32_t z;
    uint32_t state;
    int32_t slot;
} PrimeMcColorRequest;

typedef struct PrimeMcBiomeRequest {
    int32_t x;
    int32_t y;
    int32_t z;
    uint32_t reserved;
    uint64_t mask;
} PrimeMcBiomeRequest;

typedef struct PrimeMcRequests {
    PrimeMcIdentity identity;
    uint32_t phase;
    uint32_t reserved;
    uint64_t active_sections;
    const PrimeMcSectionRequest *sections;
    uint64_t section_count;
    const PrimeMcColumnRequest *columns;
    uint64_t column_count;
    const PrimeMcColorRequest *colors;
    uint64_t color_count;
    const PrimeMcBiomeRequest *biomes;
    uint64_t biome_count;
} PrimeMcRequests;

typedef struct PrimeMcColorRecipe {
    uint32_t kind;
    uint32_t value;
} PrimeMcColorRecipe;

typedef struct PrimeMcBiomeDefinitions {
    uint64_t seed;
    uint32_t permutation[256];
    double offset[2];
    double input_scale;
    double value_scale;
    PrimeMcRange grass;
    PrimeMcRange foliage;
    PrimeMcRange dry_foliage;
} PrimeMcBiomeDefinitions;

typedef struct PrimeMcColorBatch {
    PrimeMcIdentity identity;
    int32_t radius;
    uint32_t reserved;
    const PrimeMcColorRecipe *recipes;
    uint64_t recipe_count;
    const PrimeMcBiomeDefinitions *definitions;
    uint64_t definition_count;
    const uint32_t *colormaps;
    uint64_t colormap_count;
} PrimeMcColorBatch;

typedef struct PrimeMcBiome {
    float temperature;
    float downfall;
    uint32_t water;
    uint32_t overrides[3];
    uint32_t flags;
    uint32_t modifier;
} PrimeMcBiome;

typedef struct PrimeMcBiomeBatch {
    PrimeMcIdentity identity;
    const PrimeMcBiome *biomes;
    uint64_t biome_count;
    const uint32_t *indices;
    uint64_t index_count;
} PrimeMcBiomeBatch;

int32_t prime_mc_resources(uint64_t handle, const PrimeMcResourceBatch *batch);
int32_t prime_mc_plan(uint64_t handle, const PrimeMcPlan *batch, PrimeMcRequests *output);
int32_t prime_mc_sections(uint64_t handle, const PrimeMcSectionBatch *batch,
                          PrimeMcRequests *output);
int32_t prime_mc_colors(uint64_t handle, const PrimeMcColorBatch *batch, PrimeMcRequests *output);
int32_t prime_mc_biomes(uint64_t handle, const PrimeMcBiomeBatch *batch, PrimeMcRequests *output);
#endif
