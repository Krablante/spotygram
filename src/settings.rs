use crate::{i18n, model::Snapshot, ui::TouchScrollWidgetRefExt};
use makepad_widgets::*;

#[derive(Clone, Copy, Default, PartialEq, Eq)]
pub enum Section {
    #[default]
    General,
    Player,
    Storage,
    Telegram,
}

pub fn sections() -> [(Section, &'static [LiveId], &'static [LiveId]); 4] {
    [
        (
            Section::General,
            ids!(settings_general_tab),
            ids!(settings_general_page),
        ),
        (
            Section::Player,
            ids!(settings_player_tab),
            ids!(settings_player_page),
        ),
        (
            Section::Storage,
            ids!(settings_storage_tab),
            ids!(settings_storage_page),
        ),
        (
            Section::Telegram,
            ids!(settings_telegram_tab),
            ids!(settings_telegram_page),
        ),
    ]
}

fn text(ui: &WidgetRef, cx: &mut Cx, state: &Snapshot, path: &[LiveId], value: &str) {
    ui.widget(cx, path)
        .set_text(cx, &i18n::tr(&state.language, value));
}

fn choice(ui: &WidgetRef, cx: &mut Cx, path: &[LiveId], selected: bool) {
    let button = ui.widget(cx, path);
    let active = if selected { 1.0 } else { 0.0 };
    // Use Button's state track so selection keeps the existing focus area.
    ui.button(cx, path).set_open_with(cx, selected, Animate::No);
    if let Some(mut button) = button.borrow_mut::<Button>() {
        button.draw_icon.opacity = active;
    }
}

fn disabled(ui: &WidgetRef, cx: &mut Cx, state: &Snapshot, path: &[LiveId], value: bool) {
    let widget = ui.widget(cx, path);
    widget.set_disabled(cx, value);
    ui.button(cx, path).set_enabled(cx, !value);
    let hit = widget.button(cx, ids!(hit));
    hit.set_enabled(cx, !value);
    hit.set_disabled(cx, value);
    if let Some(mut button) = widget.borrow_mut::<Button>() {
        button.draw_icon.opacity = if value { 0.38 } else { 1.0 };
    } else {
        let color = match (value, state.settings["theme"] == "light") {
            (false, true) => vec4(0.114, 0.188, 0.196, 1.),
            (false, false) => vec4(0.945, 0.965, 0.953, 1.),
            (true, true) => vec4(0.61, 0.65, 0.66, 1.),
            (true, false) => vec4(0.39, 0.43, 0.45, 1.),
        };
        let mut title = widget.widget(cx, ids!(title));
        script_apply_eval!(cx, title, {draw_text.color:#(color)});
        let opacity = if value { 0.38 } else { 1.0 };
        let mut icon = widget.widget(cx, ids!(mark));
        script_apply_eval!(cx, icon, {draw_icon.opacity:#(opacity)});
    }
}

pub fn select(ui: &WidgetRef, cx: &mut Cx, selected: Section) {
    for (section, tab, page) in sections() {
        let active = selected == section;
        ui.widget(cx, page).set_visible(cx, active);
        if !active {
            ui.touch_scroll(cx, page).stop_motion();
        }
        choice(ui, cx, tab, active);
    }
}

pub fn stop(ui: &WidgetRef, cx: &mut Cx) {
    for (_, _, page) in sections() {
        ui.touch_scroll(cx, page).stop_motion();
    }
}

pub fn layout(ui: &WidgetRef, cx: &mut Cx, width: f64, height: f64) {
    // Makepad handles display density, but its Android bridge does not apply
    // the user's independent text size. Keep this scoped to the settings UI.
    #[cfg(target_os = "android")]
    let scale = crate::android::call_string("fontScale", "")
        .ok()
        .and_then(|value| value.parse::<f64>().ok())
        .filter(|value| value.is_finite() && *value > 0.)
        .unwrap_or(1.);
    #[cfg(not(target_os = "android"))]
    let scale = 1.0_f64;
    let button_height = (32. * scale).max(48.);
    let header_height = (30. * scale).max(48.);
    let inner = (width.min(960.) - 32.).max(240.);
    let wide = inner >= 700. && height >= 4. * button_height + header_height + 80.;
    let columns = if inner < 4. * (64. * scale + 28.) + 12. {
        2.0
    } else {
        4.0
    };
    let mut header = ui.widget(cx, ids!(settings_header));
    script_apply_eval!(cx, header, {height:#(header_height)});
    let flow = if wide { Flow::right() } else { Flow::Down };
    let mut body = ui.widget(cx, ids!(settings_layout));
    let spacing = if wide { 24.0 } else { 16.0 };
    script_apply_eval!(cx, body, {flow:#(flow) spacing:#(spacing)});
    let nav_width = if wide {
        Size::Fixed(176.)
    } else {
        Size::fill()
    };
    let nav_height = if wide {
        Size::fit()
    } else {
        Size::Fixed(button_height * (4. / columns) + 4. * (4. / columns - 1.))
    };
    let flow = if wide { Flow::Down } else { Flow::right_wrap() };
    let mut nav = ui.widget(cx, ids!(settings_nav));
    script_apply_eval!(cx, nav, {width:#(nav_width) height:#(nav_height) flow:#(flow)});
    let button_width = if wide {
        Size::fill()
    } else {
        Size::Fixed(((inner - 4. * (columns - 1.)) / columns).floor())
    };
    let align = Align {
        x: if wide { 0.0 } else { 0.5 },
        y: 0.5,
    };
    for (_, tab, _) in sections() {
        let mut tab = ui.widget(cx, tab);
        script_apply_eval!(cx, tab, {width:#(button_width) height:#(button_height) align:#(align)});
    }
    let content_width = inner - if wide { 200. } else { 0. } - 36.;
    let compact_language = content_width < 280. * scale;
    let vertical_language = scale > 1.4 && compact_language;
    let rows = if vertical_language {
        3.
    } else if compact_language {
        2.
    } else {
        1.
    };
    let height = rows * button_height + (rows - 1.) * 4.;
    let mut choices = ui.widget(cx, ids!(language_choices));
    let flow = Flow::right_wrap();
    script_apply_eval!(cx, choices, {height:#(height) flow:#(flow)});
    for id in [ids!(language_system), ids!(language_en), ids!(language_ru)] {
        let width = Size::Fixed(if vertical_language {
            content_width.floor()
        } else if compact_language {
            if id == ids!(language_system) {
                content_width.floor()
            } else {
                ((content_width - 4.) / 2.).floor()
            }
        } else {
            ((content_width - 8.) / 3.).floor()
        });
        let mut button = ui.widget(cx, id);
        script_apply_eval!(cx, button, {width:#(width) height:#(button_height)});
    }
    let mut theme = ui.widget(cx, ids!(settings_theme_choices));
    script_apply_eval!(cx, theme, {height:#(button_height)});
    for id in [ids!(theme_light), ids!(theme_dark)] {
        let mut button = ui.widget(cx, id);
        script_apply_eval!(cx, button, {height:#(button_height)});
    }
    let stack_actions = content_width < 300. * scale;
    let flow = if stack_actions {
        Flow::Down
    } else {
        Flow::right()
    };
    let mut actions = ui.widget(cx, ids!(settings_app_actions));
    script_apply_eval!(cx, actions, {flow:#(flow)});
    for (id, normal_width) in [
        (ids!(settings_update).as_slice(), 220.),
        (ids!(settings_quit), 80.),
    ] {
        let width = if stack_actions {
            Size::fill()
        } else {
            Size::Fixed(normal_width * scale)
        };
        let mut button = ui.widget(cx, id);
        script_apply_eval!(cx, button, {width:#(width)});
    }
    let font = 18. * scale;
    let mut title = ui.widget(cx, ids!(settings_title));
    script_apply_eval!(cx, title, {draw_text.text_style.font_size:#(font)});
    let font = 12. * scale;
    for id in [
        ids!(settings_appearance),
        ids!(settings_language_label),
        ids!(settings_library_label),
        ids!(settings_application_label),
        ids!(settings_playback_label),
        ids!(settings_sharing_label),
        ids!(settings_saved_label),
        ids!(settings_downloads_label),
        ids!(settings_cache_label),
        ids!(settings_account_label),
        ids!(settings_sync_label),
        ids!(settings_connection),
        ids!(settings_sync),
        ids!(settings_error),
    ] {
        let mut label = ui.widget(cx, id);
        let height = Size::fit();
        script_apply_eval!(cx, label, {height:#(height) max_lines:6 draw_text.text_style.font_size:#(font)});
    }
    for id in [
        ids!(settings_duplicates),
        ids!(settings_bookmarks),
        ids!(settings_minutes),
        ids!(settings_caption),
        ids!(settings_sender),
        ids!(settings_storage),
        ids!(settings_wifi),
        ids!(settings_discard),
    ] {
        let mut title = ui.widget(cx, id).widget(cx, ids!(title));
        script_apply_eval!(cx, title, {draw_text.text_style.font_size:#(font) max_lines:6});
        let mut detail = ui.widget(cx, id).widget(cx, ids!(detail));
        let font = 10.5 * scale;
        script_apply_eval!(cx, detail, {draw_text.text_style.font_size:#(font)});
    }
    let font = 10.5 * scale;
    for id in [
        ids!(settings_about),
        ids!(settings_update_result),
        ids!(cache_count),
        ids!(settings_cache_help),
        ids!(settings_transfers_status),
    ] {
        let mut label = ui.widget(cx, id);
        script_apply_eval!(cx, label, {draw_text.text_style.font_size:#(font)});
    }
    for id in [
        ids!(settings_general_tab),
        ids!(settings_player_tab),
        ids!(settings_storage_tab),
        ids!(settings_telegram_tab),
        ids!(theme_light),
        ids!(theme_dark),
        ids!(language_system),
        ids!(language_en),
        ids!(language_ru),
        ids!(settings_transfers),
    ] {
        let mut button = ui.widget(cx, id);
        script_apply_eval!(cx, button, {draw_text.text_style.font_size:#(font)});
    }
    for id in [
        ids!(settings_update),
        ids!(settings_quit),
        ids!(settings_import),
        ids!(cache_clear),
        ids!(settings_cache_connect),
        ids!(settings_account),
        ids!(settings_sync_refresh),
    ] {
        let mut label = ui.widget(cx, id).widget(cx, ids!(title));
        script_apply_eval!(cx, label, {draw_text.text_style.font_size:#(font)});
    }
    let font = 15. * scale;
    let mut size = ui.widget(cx, ids!(cache_size));
    script_apply_eval!(cx, size, {draw_text.text_style.font_size:#(font)});
    let height = 48. * scale.max(1.);
    for id in [ids!(settings_cache_stats), ids!(settings_transfer_row)] {
        let mut row = ui.widget(cx, id);
        script_apply_eval!(cx, row, {height:#(height)});
    }
}

pub fn refresh(ui: &WidgetRef, cx: &mut Cx, state: &Snapshot) {
    for (id, label) in [
        (ids!(settings_title).as_slice(), "Settings"),
        (ids!(settings_general_tab), "General"),
        (ids!(settings_player_tab), "Player"),
        (ids!(settings_storage_tab), "Storage"),
        (ids!(settings_telegram_tab), "Telegram"),
        (ids!(settings_appearance), "Appearance"),
        (ids!(theme_light), "Light"),
        (ids!(theme_dark), "Dark"),
        (ids!(settings_language_label), "Language"),
        (ids!(language_system), "System"),
        (ids!(settings_library_label), "Library"),
        (ids!(settings_duplicates), "Hide duplicates"),
        (
            ids!(settings_duplicates.detail),
            "One entry per recording. Files stay on this device.",
        ),
        (ids!(settings_application_label), "Application"),
        (ids!(settings_update), "Check for updates"),
        (ids!(settings_quit), "Quit"),
        (ids!(settings_playback_label), "Playback"),
        (ids!(settings_bookmarks), "Remember audio positions"),
        (
            ids!(settings_bookmarks.detail),
            "Continue long recordings where you stopped.",
        ),
        (ids!(settings_minutes), "Audio duration threshold"),
        (ids!(settings_sharing_label), "Sharing audio"),
        (ids!(settings_caption), "Keep original caption"),
        (ids!(settings_sender), "Keep original sender"),
        (
            ids!(settings_sender.detail),
            "Also keeps the original caption.",
        ),
        (ids!(settings_saved_label), "Saved music"),
        (ids!(settings_storage), "On device"),
        (ids!(settings_import), "Import audio files"),
        (ids!(settings_downloads_label), "Downloads"),
        (ids!(settings_wifi), "Downloads over Wi-Fi only"),
        (ids!(settings_wifi.detail), "Streaming can use mobile data."),
        (ids!(settings_discard), "Do not keep played tracks"),
        (
            ids!(settings_discard.detail),
            "Saved downloads and imports stay.",
        ),
        (ids!(settings_transfers), "Cancel"),
        (ids!(settings_cache_label), "Music cache"),
        (ids!(cache_clear), "Clear music cache"),
        (ids!(settings_cache_connect), "Connect Telegram"),
        (ids!(settings_account_label), "Account"),
        (ids!(settings_sync_label), "Library sync"),
        (ids!(settings_sync_refresh), "Refresh library"),
    ] {
        text(ui, cx, state, id, label);
    }
    text(
        ui,
        cx,
        state,
        ids!(settings_about),
        &format!("spotygram rust · {}", env!("CARGO_PKG_VERSION")),
    );
    for (id, selected) in [
        (ids!(theme_light), state.settings["theme"] == "light"),
        (ids!(theme_dark), state.settings["theme"] != "light"),
        (
            ids!(language_system),
            state.settings["language"] == "system",
        ),
        (ids!(language_en), state.settings["language"] == "en"),
        (ids!(language_ru), state.settings["language"] == "ru"),
    ] {
        choice(ui, cx, id, selected);
    }
    for (id, key) in [
        (ids!(settings_duplicates), "hide_duplicates"),
        (ids!(settings_bookmarks), "resume_long_audio"),
        (ids!(settings_wifi), "wifi_only"),
        (ids!(settings_discard), "discard_listened"),
        (ids!(settings_sender), "sharing_sender"),
        (ids!(settings_caption), "sharing_caption"),
    ] {
        let checked = if state.settings[key] == true {
            1.0
        } else {
            0.0
        };
        let mut toggle = ui.widget(cx, id).widget(cx, ids!(toggle));
        script_apply_eval!(cx, toggle, {draw_bg.checked:#(checked)});
    }
    disabled(
        ui,
        cx,
        state,
        ids!(settings_minutes),
        state.settings["resume_long_audio"] != true,
    );
    let minutes = state.settings["resume_minutes"].as_u64().unwrap_or(30);
    let threshold = if state.language == "ru" {
        format!("От {minutes} мин")
    } else {
        format!("{minutes} min and longer")
    };
    text(ui, cx, state, ids!(settings_minutes.detail), &threshold);
    text(
        ui,
        cx,
        state,
        ids!(settings_storage.detail),
        &format!(
            "{} · {}",
            i18n::tracks(&state.language, state.storage_count),
            i18n::bytes(&state.language, state.storage_bytes.max(0) as u64)
        ),
    );
    let transfers = if state.downloads == 0 {
        i18n::tr(&state.language, "No active downloads").into_owned()
    } else {
        format!(
            "{} · {:.0}%",
            i18n::tracks(&state.language, state.downloads),
            state.transfer_progress * 100.
        )
    };
    text(ui, cx, state, ids!(settings_transfers_status), &transfers);
    disabled(
        ui,
        cx,
        state,
        ids!(settings_transfers),
        state.downloads == 0,
    );
    let connected = state.auth.kind == "authorizationStateReady";
    text(
        ui,
        cx,
        state,
        ids!(settings_connection),
        if connected {
            "Telegram is connected."
        } else {
            "Not connected"
        },
    );
    text(
        ui,
        cx,
        state,
        ids!(settings_account),
        if connected {
            "Sign out of Telegram"
        } else {
            "Connect Telegram"
        },
    );
    text(
        ui,
        cx,
        state,
        ids!(settings_sync),
        if connected {
            &state.sync
        } else {
            "Connect Telegram to sync your library."
        },
    );
    disabled(
        ui,
        cx,
        state,
        ids!(settings_sync_refresh),
        !connected || state.busy,
    );
    text(
        ui,
        cx,
        state,
        ids!(cache_size),
        &state
            .cache_size
            .map(|(bytes, _)| i18n::bytes(&state.language, bytes))
            .unwrap_or_else(|| "—".into()),
    );
    text(
        ui,
        cx,
        state,
        ids!(cache_count),
        &if !connected {
            String::new()
        } else if state.cache_loading {
            i18n::tr(&state.language, "Calculating…").into_owned()
        } else if !state.cache_error.is_empty() {
            state.cache_error.clone()
        } else {
            state
                .cache_size
                .map(|(_, files)| i18n::files(&state.language, files))
                .unwrap_or_default()
        },
    );
    text(
        ui,
        cx,
        state,
        ids!(settings_cache_help),
        if connected {
            "Telegram audio. Imported files stay."
        } else {
            "Connect Telegram to manage its downloaded audio."
        },
    );
    ui.widget(cx, ids!(cache_loading))
        .set_visible(cx, state.cache_loading);
    ui.widget(cx, ids!(cache_clear)).set_visible(cx, connected);
    ui.widget(cx, ids!(settings_cache_connect))
        .set_visible(cx, !connected);
    disabled(
        ui,
        cx,
        state,
        ids!(cache_clear),
        state.cache_loading
            || state.cache_size.is_none_or(|(bytes, _)| bytes == 0)
            || !state.cache_error.is_empty(),
    );
    disabled(
        ui,
        cx,
        state,
        ids!(cache_refresh),
        !connected || state.cache_loading,
    );
    ui.widget(cx, ids!(settings_error))
        .set_visible(cx, !state.error.is_empty());
    text(ui, cx, state, ids!(settings_error), &state.error);
    let update_result = matches!(
        state.status.as_str(),
        "You have the latest Rust test build" | "A newer Rust test build is available"
    );
    ui.widget(cx, ids!(settings_update_result))
        .set_visible(cx, update_result);
    text(ui, cx, state, ids!(settings_update_result), &state.status);
}
