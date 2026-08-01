/*
 * Mosaic Link live-digital runtime for HK8 PRO MAX firmware 2.09.
 *
 * The watchface lifecycle and widget layouts were recovered from several
 * official modules.  Keep this source freestanding: the target resolves all
 * undefined symbols from its firmware when the dlmodule is loaded.
 */
#include <stdint.h>
#include <stddef.h>

#define MODULE_NAME "wf_clock443"
#define CONTEXT_BYTES 28u
#define NUMBER_SLOT_COUNT 9u
#define SEQUENCE_SLOT_COUNT 3u
#ifndef DIGITAL_RUNTIME_STAGE
#define DIGITAL_RUNTIME_STAGE 4
#endif

typedef void lv_obj_t;
typedef void rt_dlmodule_t;

typedef struct {
    uint16_t type;
    uint16_t x;
    uint16_t y;
    uint16_t reserved;
    const char *path;
} image_config_t;

typedef struct {
    const char *path;
    const char *center_path;
    uint16_t pivot_x;
    uint16_t pivot_y;
} analog_hand_t;

typedef struct {
    uint16_t type;
    uint16_t center_x;
    uint16_t center_y;
    uint16_t reserved;
    const analog_hand_t *hands;
} analog_config_t;

typedef char *(*number_value_cb_t)(void);

/* Matches the 20-byte configs passed to wf_obj_img_number_create by stock. */
typedef struct {
    uint16_t type;
    uint16_t x;
    uint16_t y;
    uint16_t reserved;
    const char *const *digits;
    uint16_t max_chars;
    uint16_t refresh;
    number_value_cb_t value_cb;
} number_config_t;

typedef uint8_t (*sequence_index_cb_t)(void);

typedef struct {
    uint16_t type;
    uint16_t x;
    uint16_t y;
    uint16_t reserved;
    const char *const *images;
    uint16_t count;
    uint16_t refresh;
    uint32_t reserved0;
    uint32_t reserved1;
    sequence_index_cb_t index_cb;
    uint32_t reserved2;
    uint32_t reserved3;
} sequence_config_t;

typedef struct {
    uint8_t marker[16];
    number_config_t numbers[NUMBER_SLOT_COUNT];
    sequence_config_t sequences[SEQUENCE_SLOT_COUNT];
} patchable_layout_t;

typedef char check_number_config_size[(sizeof(number_config_t) == 20u) ? 1 : -1];
typedef char check_sequence_config_size[(sizeof(sequence_config_t) == 36u) ? 1 : -1];

extern void *dlmodule_get_user_data(const char *name, uint32_t size);
extern void dlmodule_free_user_data(const char *name);
extern void dlmodule_set_res_mng(rt_dlmodule_t *module, void *manager);
extern void *dlmodule_get_res_mng(rt_dlmodule_t *module);
extern void *rt_malloc(uint32_t size);
extern void rt_free(void *memory);
extern void lv_ext_res_mng_builtin_init(void *manager, const void *language_pack);
extern void lv_ext_res_mng_builtin_destroy(void *manager);
extern const uint8_t lv_i18n_lang_pack[];
extern const char *lv_ext_get_locale(void);
extern void lv_ext_set_locale(void *manager, const char *locale);

extern void wf_view_init(void *context, lv_obj_t *parent);
extern void wf_view_pause(void *context);
extern void wf_view_resume(void *context);
extern void wf_view_deinit(void *context);
extern void wf_obj_img_create(void *context, const image_config_t *config);
extern void wf_obj_img_number_create(void *context, const number_config_t *config);
extern void wf_obj_img_sequence_create(void *context, const sequence_config_t *config);
extern void wf_obj_analog_clock_create(void *context, const analog_config_t *config);
extern int app_tileview_only_change_anim_status(void *page, int state);
extern void app_tileview_register_watchface(
    const char *name,
    int reserved,
    const char *thumbnail_path,
    void (*message_handler)(int, void *)
);

extern const uint8_t *wf_get_date_time(void);
extern char *wf_get_str_buf(void);
extern int snprintf(char *buffer, uint32_t size, const char *format, ...);
extern const uint8_t *app_db_get_rt_data(int kind);

#define DIGIT_PATHS(slot) \
    "/ex/resource/wf_clock443/s" #slot "_0.bin", \
    "/ex/resource/wf_clock443/s" #slot "_1.bin", \
    "/ex/resource/wf_clock443/s" #slot "_2.bin", \
    "/ex/resource/wf_clock443/s" #slot "_3.bin", \
    "/ex/resource/wf_clock443/s" #slot "_4.bin", \
    "/ex/resource/wf_clock443/s" #slot "_5.bin", \
    "/ex/resource/wf_clock443/s" #slot "_6.bin", \
    "/ex/resource/wf_clock443/s" #slot "_7.bin", \
    "/ex/resource/wf_clock443/s" #slot "_8.bin", \
    "/ex/resource/wf_clock443/s" #slot "_9.bin"

static const char *const slot0_digits[] = { DIGIT_PATHS(0) };
static const char *const slot1_digits[] = { DIGIT_PATHS(1) };
static const char *const slot2_digits[] = { DIGIT_PATHS(2) };
static const char *const slot3_digits[] = { DIGIT_PATHS(3) };
static const char *const slot4_digits[] = { DIGIT_PATHS(4) };
static const char *const slot5_digits[] = { DIGIT_PATHS(5) };
static const char *const slot6_digits[] = { DIGIT_PATHS(6) };
static const char *const slot7_digits[] = { DIGIT_PATHS(7) };
static const char *const slot8_digits[] = { DIGIT_PATHS(8) };

#define SEQUENCE_PATHS_7(slot) \
    "/ex/resource/wf_clock443/q" #slot "_0.bin", \
    "/ex/resource/wf_clock443/q" #slot "_1.bin", \
    "/ex/resource/wf_clock443/q" #slot "_2.bin", \
    "/ex/resource/wf_clock443/q" #slot "_3.bin", \
    "/ex/resource/wf_clock443/q" #slot "_4.bin", \
    "/ex/resource/wf_clock443/q" #slot "_5.bin", \
    "/ex/resource/wf_clock443/q" #slot "_6.bin"

#define SEQUENCE_PATHS_9(slot) \
    SEQUENCE_PATHS_7(slot), \
    "/ex/resource/wf_clock443/q" #slot "_7.bin", \
    "/ex/resource/wf_clock443/q" #slot "_8.bin"

#define SEQUENCE_PATHS_11(slot) \
    SEQUENCE_PATHS_9(slot), \
    "/ex/resource/wf_clock443/q" #slot "_9.bin", \
    "/ex/resource/wf_clock443/q" #slot "_10.bin"

#define SEQUENCE_PATHS_12(slot) \
    SEQUENCE_PATHS_11(slot), \
    "/ex/resource/wf_clock443/q" #slot "_11.bin"

static const char *const month_images[] = { SEQUENCE_PATHS_12(0) };
static const char *const weekday_images[] = { SEQUENCE_PATHS_7(1) };
static const char *const battery_bar_images[] = { SEQUENCE_PATHS_11(2) };

static char *format_u32(const char *format, uint32_t value)
{
    char *buffer = wf_get_str_buf();
    if (!buffer) return NULL;
    snprintf(buffer, 199u, format, value);
    return buffer;
}

static uint16_t read_u16_le(const uint8_t *data)
{
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static uint32_t read_u32_le(const uint8_t *data)
{
    return (uint32_t)data[0] |
        ((uint32_t)data[1] << 8) |
        ((uint32_t)data[2] << 16) |
        ((uint32_t)data[3] << 24);
}

static char *hour_value(void)
{
    const uint8_t *time = wf_get_date_time();
    return format_u32("%02u", time ? time[4] : 0u);
}

static char *minute_value(void)
{
    const uint8_t *time = wf_get_date_time();
    return format_u32("%02u", time ? time[5] : 0u);
}

static char *seconds_value(void)
{
    const uint8_t *time = wf_get_date_time();
    return format_u32("%02u", time ? time[6] : 0u);
}

static char *day_value(void)
{
    const uint8_t *time = wf_get_date_time();
    return format_u32("%02u", time ? time[3] : 1u);
}

static char *battery_value(void)
{
    const uint8_t *data = app_db_get_rt_data(5);
    return format_u32("%u", data ? read_u16_le(data + 2) : 0u);
}

static char *steps_value(void)
{
    const uint8_t *data = app_db_get_rt_data(0);
    return format_u32("%u", data ? read_u32_le(data + 0) : 0u);
}

static char *calories_value(void)
{
    const uint8_t *data = app_db_get_rt_data(0);
    return format_u32("%u", data ? read_u32_le(data + 4) % 10000u : 0u);
}

static char *heart_value(void)
{
    const uint8_t *data = app_db_get_rt_data(1);
    return format_u32("%u", data ? data[1] : 0u);
}

static char *distance_value(void)
{
    const uint8_t *data = app_db_get_rt_data(0);
    uint32_t value = data ? read_u32_le(data + 8) : 0u;
    /* Clock2's medium distance text is rendered by Android as d.d + "mi".
     * The image-number widget receives only those two digits; punctuation is
     * part of the exact-font static overlay. */
    return format_u32("%02u", (value / 10u) % 100u);
}

static uint8_t month_index(void)
{
    const uint8_t *time = wf_get_date_time();
    uint8_t month = time ? time[2] : 1u;
    return (month >= 1u && month <= 12u) ? (uint8_t)(month - 1u) : 0u;
}

static uint8_t weekday_index(void)
{
    const uint8_t *time = wf_get_date_time();
    uint8_t weekday = time ? time[10] : 0u;
    return weekday <= 6u ? weekday : 0u;
}

static uint8_t battery_bar_index(void)
{
    const uint8_t *data = app_db_get_rt_data(5);
    uint16_t value = data ? read_u16_le(data + 2) : 0u;
    if (value > 100u) value = 100u;
    return (uint8_t)(value / 10u);
}

static const image_config_t background = {
    6, 0, 0, 0, "/ex/resource/wf_clock443/background.bin"
};

static const char *const center_path =
    "/ex/resource/wf_clock443/center.bin";
static const analog_hand_t main_hands[] = {
    { "/ex/resource/wf_clock443/main_h.bin", center_path, 15, 126 },
    { "/ex/resource/wf_clock443/main_m.bin", center_path, 15, 202 },
    { "/ex/resource/wf_clock443/main_s.bin", center_path, 6, 202 },
};
static const analog_config_t main_clock = { 5, 205, 247, 0, main_hands };

/*
 * x/y are deliberately off-screen in the audited template. Android patches
 * only these 16-bit fields after checking the unique marker and ELF hash.
 */
__attribute__((used))
static patchable_layout_t layout = {
    { 'M','O','S','A','I','C','-','D','I','G','I','T','A','L','0','2' },
    {
        { 20, 600, 600, 0, slot0_digits, 2, 5, hour_value },
        { 21, 600, 600, 0, slot1_digits, 2, 5, minute_value },
        { 22, 600, 600, 0, slot2_digits, 2, 5, seconds_value },
        { 23, 600, 600, 0, slot3_digits, 2, 5, day_value },
        { 24, 600, 600, 0, slot4_digits, 3, 5, battery_value },
        { 25, 600, 600, 0, slot5_digits, 6, 5, steps_value },
        { 26, 600, 600, 0, slot6_digits, 4, 5, calories_value },
        { 27, 600, 600, 0, slot7_digits, 3, 5, heart_value },
        { 28, 600, 600, 0, slot8_digits, 2, 5, distance_value },
    },
    {
        { 40, 600, 600, 0, month_images, 12, 5, 0, 0, month_index, 0, 0 },
        { 41, 600, 600, 0, weekday_images, 7, 5, 0, 0, weekday_index, 0, 0 },
        { 42, 600, 600, 0, battery_bar_images, 11, 5, 0, 0, battery_bar_index, 0, 0 },
    }
};

static void on_start(lv_obj_t *parent)
{
    uint32_t index;
    void *context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
    wf_view_init(context, parent);
#if DIGITAL_RUNTIME_STAGE >= 2
    context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
    wf_obj_img_create(context, &background);
#endif
#if DIGITAL_RUNTIME_STAGE == 3
    context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
    wf_obj_analog_clock_create(context, &main_clock);
#endif
#if DIGITAL_RUNTIME_STAGE >= 4
    for (index = 0; index < NUMBER_SLOT_COUNT; ++index) {
        context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
        wf_obj_img_number_create(context, &layout.numbers[index]);
    }
    for (index = 0; index < SEQUENCE_SLOT_COUNT; ++index) {
        context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
        wf_obj_img_sequence_create(context, &layout.sequences[index]);
    }
#else
    (void)index;
#endif
}

static void message_handler(int message, void *parameter)
{
    void *context;
    switch (message) {
    case 0:
        on_start(*(lv_obj_t **)((uint8_t *)parameter + 0x24));
        break;
    case 1:
        if (app_tileview_only_change_anim_status(parameter, 1) == 0) {
            context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
            wf_view_pause(context);
        }
        break;
    case 2:
        if (app_tileview_only_change_anim_status(parameter, 2) == 0) {
            context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
            wf_view_resume(context);
        }
        break;
    case 3:
        context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
        wf_view_deinit(context);
        context = dlmodule_get_user_data(MODULE_NAME, CONTEXT_BYTES);
        if (context) dlmodule_free_user_data(MODULE_NAME);
        break;
    default:
        break;
    }
}

void wf_clock443_register(void)
{
    app_tileview_register_watchface(
        MODULE_NAME,
        0,
        "/ex/installer_wf/wf_clock443_tn.bin",
        message_handler
    );
}

void module_init(rt_dlmodule_t *module)
{
    void *resource_manager = rt_malloc(44u);
    dlmodule_set_res_mng(module, resource_manager);
    lv_ext_res_mng_builtin_init(resource_manager, lv_i18n_lang_pack);
    lv_ext_set_locale(resource_manager, lv_ext_get_locale());
    wf_clock443_register();
}

void module_cleanup(rt_dlmodule_t *module)
{
    void *resource_manager = dlmodule_get_res_mng(module);
    if (resource_manager) {
        lv_ext_res_mng_builtin_destroy(resource_manager);
        rt_free(resource_manager);
    }
}
