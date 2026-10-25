use crate::settings::Section as SettingsSection;
use crate::ui::{MediaSliderWidgetRefExt, TouchScrollWidgetRefExt};
use crate::{model as data, runtime, settings, ui};
use makepad_widgets::makepad_platform::event::finger::TouchState;
use makepad_widgets::nav_control::NavControl;
use makepad_widgets::*;
use serde_json::json;
use std::{
    collections::{HashSet, VecDeque},
    sync::Arc,
};

app_main!(App);

script_mod! {
    use mod.prelude.widgets.*
    use mod.spotygram.*
    startup() do #(App::script_component(vm)) {
        settings_nav_control: mod.widgets.NavControl{}
        ui: Root {
            main_window := Window {
                window.title: "spotygram rust"
                window.app_id: "app.spotygram.rust"
                window.inner_size: vec2(1040,760)
                pass.clear_color: theme.color_bg_app
                caption_bar +: {windows_buttons +: {
                    // The stock caption closes the window directly, bypassing
                    // WindowCloseRequested and the app's playback lifetime.
                    close +: {visible:false}
                    app_close := DesktopButton {
                        draw_bg.button_type: DesktopButtonType.WindowsClose
                        width:46 height:29
                        draw_bg +: {
                            color:theme.color_label_inner color_hover:#FFF color_down:#FFF
                            bg_color_hover:#E81123 bg_color_down:#F1707A
                        }
                    }
                }}
                body +: {root := RootView{}}
            }
        }
    }
}

#[derive(Default, Clone, PartialEq)]
enum Dialog {
    #[default]
    None,
    Settings,
    Auth,
    Track,
    Chats,
    Profile,
    Filter,
    Sort,
    Playlists,
    RenamePlaylist(i64),
    PlaylistActions(i64),
    Recipients,
    Folders(bool),
    Topics(i64, String),
    Delete,
    DeletePlaylist(i64),
    ClearCache,
    Logout,
    Speed,
    Order,
    Repeat,
    BookmarkThreshold,
    RemoveSource(i64, bool),
    Sharing,
    AddSource,
    Queue,
    DiscardEditor,
}

struct PlaylistEditor {
    playlist: Option<i64>,
    previous: data::Query,
    ids: Vec<String>,
    pending: Option<(data::Query, bool)>,
    all_selected: Option<data::Query>,
    saving: bool,
    selection_revision: u64,
}

struct SettingsLibraryReturn {
    query: data::Query,
    page: usize,
    first: usize,
    scroll: f64,
    full_player: bool,
    restoring: bool,
}

#[derive(Script, ScriptHook)]
pub struct App {
    #[live]
    settings_nav_control: NavControl,
    #[live]
    ui: WidgetRef,
    #[rust]
    client: Option<Arc<runtime::Client>>,
    #[rust]
    state: Option<Arc<data::Snapshot>>,
    #[rust]
    pending: VecDeque<data::Command>,
    #[rust]
    dialog: Dialog,
    #[rust]
    settings_open: bool,
    #[rust]
    settings_section: SettingsSection,
    #[rust]
    settings_library_return: Option<SettingsLibraryReturn>,
    #[rust]
    full_player: bool,
    #[rust]
    selected: Option<data::Track>,
    #[rust]
    selected_position: usize,
    #[rust]
    selection: HashSet<String>,
    #[rust]
    selecting: bool,
    #[rust]
    choices: Vec<(i64, String)>,
    #[rust]
    folder: i32,
    #[rust]
    size: Vec2d,
    #[rust]
    dark: Option<bool>,
    #[rust]
    quitting: bool,
    #[rust]
    closed: bool,
    #[rust]
    search_timer: Timer,
    #[rust]
    directory_timer: Timer,
    #[rust]
    image_paths: HashSet<String>,
    #[rust]
    qr_url: String,
    #[rust]
    panel_wide: Option<bool>,
    #[rust]
    sticker: Option<usize>,
    #[rust]
    hidden_at: Option<std::time::Instant>,
    #[rust]
    sheet_drag: Option<Vec2d>,
    #[rust]
    sheet_offset: f64,
    #[rust]
    panel_anchor: Option<Rect>,
    #[rust]
    safe: Inset,
    #[rust]
    pending_selection: Option<(data::Query, bool)>,
    #[rust]
    all_selected_query: Option<data::Query>,
    #[rust]
    filter_sources: Vec<data::Source>,
    #[rust]
    send_target: Option<(i64, i64, String)>,
    #[rust]
    sharing_return: bool,
    #[rust]
    quick_recipients: Vec<data::Source>,
    #[rust]
    queue_return: Option<data::Query>,
    #[rust]
    queue_restore: Option<data::Query>,
    #[rust]
    keyboard_height: f64,
    #[rust]
    keyboard_visible: bool,
    #[rust]
    editor: Option<PlaylistEditor>,
}

impl App {
    fn header_state(&self, cx: &mut Cx, state: &data::Snapshot) {
        let screen = &state.page.query.screen;
        let title = if self.selecting {
            format!("{} selected", self.selection.len())
        } else {
            match screen {
                data::Screen::Music => "Music".into(),
                data::Screen::Favorites => "Favorites".into(),
                data::Screen::Playlists => "Playlists".into(),
                data::Screen::OnDevice => "On device".into(),
                data::Screen::Sources => "Sources".into(),
                data::Screen::Queue => "Queue".into(),
                data::Screen::PlaylistEditor(_) => "New playlist".into(),
                data::Screen::Playlist(id) => state
                    .playlists
                    .iter()
                    .find(|p| p.id == *id)
                    .map(|p| p.name.clone())
                    .unwrap_or_else(|| crate::i18n::tr(&state.language, "Playlist").into_owned()),
                data::Screen::Profile(id) => state
                    .sources
                    .iter()
                    .find(|s| s.profile && s.id == *id)
                    .map(|s| s.title.clone())
                    .unwrap_or_else(|| {
                        crate::i18n::tr(&state.language, "Profile music").into_owned()
                    }),
            }
        };
        self.text(cx, ids!(heading), &title);
        self.text(
            cx,
            ids!(count),
            &crate::i18n::tracks(&state.language, state.page.total),
        );
        self.visible(
            cx,
            ids!(count),
            !self.selecting
                && !matches!(
                    screen,
                    data::Screen::Sources | data::Screen::Playlists | data::Screen::Profile(_)
                ),
        );
        self.visible(
            cx,
            ids!(refresh),
            !self.selecting && !matches!(screen, data::Screen::Playlists | data::Screen::Sources),
        );
        self.visible(
            cx,
            ids!(create_playlist),
            *screen == data::Screen::Playlists,
        );
        self.visible(
            cx,
            ids!(favorite_heading),
            !self.selecting && *screen == data::Screen::Favorites,
        );
        self.visible(
            cx,
            ids!(back),
            self.selecting
                || matches!(
                    screen,
                    data::Screen::Playlist(_)
                        | data::Screen::Profile(_)
                        | data::Screen::Queue
                        | data::Screen::OnDevice
                ),
        );
        self.visible(
            cx,
            ids!(music_controls),
            !self.selecting
                && matches!(
                    screen,
                    data::Screen::Music
                        | data::Screen::Favorites
                        | data::Screen::OnDevice
                        | data::Screen::Playlist(_)
                        | data::Screen::Profile(_)
                ),
        );
        self.visible(
            cx,
            ids!(filters),
            !self.selecting && matches!(screen, data::Screen::Music | data::Screen::OnDevice),
        );
        self.visible(
            cx,
            ids!(profile_header),
            !self.selecting && matches!(screen, data::Screen::Profile(_)),
        );
        self.visible(cx, ids!(selection_controls), self.selecting);
    }
    fn anchor(&self, cx: &Cx, path: &[LiveId]) -> Option<Rect> {
        let area = self.ui.widget(cx, path).area();
        if area.is_valid(cx) {
            let rect = area.rect(cx);
            if rect.size.x > 0. && rect.size.y > 0. {
                return Some(rect);
            }
        }
        None
    }
    fn drag_sheet(&mut self, cx: &mut Cx, event: &Event) -> bool {
        if self.dialog == Dialog::None
            || self.dialog == Dialog::Auth
            || self.panel_wide != Some(false)
            || !self.ui.widget(cx, ids!(drag_handle)).visible()
        {
            return false;
        }
        let sample = match event {
            Event::MouseDown(e) => Some((0, e.abs)),
            Event::MouseMove(e) => Some((1, e.abs)),
            Event::MouseUp(e) => Some((2, e.abs)),
            Event::TouchUpdate(e) => e.touches.iter().find_map(|t| match t.state {
                TouchState::Start => Some((0, t.abs)),
                TouchState::Move => Some((1, t.abs)),
                TouchState::Stop => Some((2, t.abs)),
                _ => None,
            }),
            _ => None,
        };
        let Some((phase, position)) = sample else {
            return false;
        };
        if phase == 0 {
            let panel = self.ui.widget(cx, ids!(panel)).area().rect(cx);
            if panel.contains(position)
                && position.y < panel.pos.y + 48.
                && position.x < panel.pos.x + panel.size.x - 64.
            {
                self.sheet_drag = Some(position);
                self.sheet_offset = 0.;
                return true;
            }
        } else if let Some(start) = self.sheet_drag {
            self.sheet_offset = (position.y - start.y).clamp(0., self.size.y * 0.7);
            if phase == 2 {
                self.sheet_drag = None;
                if self.sheet_offset >= 80. {
                    self.close_dialog(cx);
                } else {
                    self.sheet_offset = 0.;
                    self.layout(cx);
                }
            } else {
                let padding = Inset {
                    bottom: -self.sheet_offset,
                    ..Default::default()
                };
                let mut layer = self.ui.widget(cx, ids!(panel_position));
                script_apply_eval!(cx,layer,{padding:#(padding)});
            }
            return true;
        }
        false
    }
    fn choose_sticker(&mut self, cx: &mut Cx) {
        let previous = self.sticker.or_else(|| {
            self.snapshot().settings["header_sticker"]
                .as_u64()
                .map(|v| v as usize)
        });
        let count = crate::design::STICKERS.len();
        let previous = previous.filter(|p| *p < count);
        let choice = rand::random_range(0..count - usize::from(previous.is_some()));
        let choice = if previous.is_some_and(|p| choice >= p) {
            choice + 1
        } else {
            choice
        };
        self.sticker = Some(choice);
        let _ = self
            .ui
            .image(cx, ids!(header_sticker))
            .load_png_from_data(cx, crate::design::STICKERS[choice]);
        self.send(
            cx,
            data::Command::Setting("header_sticker".into(), json!(choice)),
        );
    }
    fn send(&mut self, cx: &mut Cx, command: data::Command) {
        let client = self.client.get_or_insert_with(runtime::start);
        match client.commands.try_send(command) {
            Ok(()) => {}
            Err(tokio::sync::mpsc::error::TrySendError::Full(command)) => {
                if self.pending.len() < 64 {
                    self.pending.push_back(command);
                    cx.new_next_frame();
                } else {
                    self.ui
                        .label(cx, ids!(notice))
                        .set_text(cx, "The application is busy; try again shortly.");
                    self.ui.view(cx, ids!(notice)).set_visible(cx, true);
                }
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(command)) => {
                if matches!(command, data::Command::Quit) {
                    self.closed = true;
                    cx.quit();
                }
            }
        }
    }

    fn click(&self, cx: &mut Cx, actions: &Actions, path: &[LiveId]) -> bool {
        self.ui.button(cx, path).clicked(actions)
            || self
                .ui
                .widget(cx, path)
                .button(cx, ids!(hit))
                .clicked(actions)
    }
    fn text(&self, cx: &mut Cx, path: &[LiveId], text: &str) {
        let state = self.snapshot();
        let literal = [
            ids!(track_title),
            ids!(track_artist),
            ids!(mini_title),
            ids!(mini_artist),
            ids!(player_title),
            ids!(player_artist),
            ids!(send_title),
            ids!(send_artist),
            ids!(editor_playlist_name),
        ]
        .iter()
        .any(|p| p.as_slice() == path)
            || path == ids!(heading)
                && !self.selecting
                && matches!(
                    state.page.query.screen,
                    data::Screen::Playlist(_) | data::Screen::Profile(_)
                )
            || path == ids!(panel_title) && matches!(self.dialog, Dialog::PlaylistActions(_));
        let literal = literal
            || path == ids!(playing_source)
                && state
                    .playback
                    .track
                    .as_ref()
                    .is_some_and(|track| !track.imported());
        if literal {
            self.ui.widget(cx, path).set_text(cx, text);
        } else {
            self.ui
                .widget(cx, path)
                .set_text(cx, &crate::i18n::tr(&state.language, text));
        }
    }
    fn visible(&self, cx: &mut Cx, path: &[LiveId], visible: bool) {
        self.ui.widget(cx, path).set_visible(cx, visible);
    }
    fn snapshot(&self) -> Arc<data::Snapshot> {
        self.state.clone().unwrap_or_default()
    }
    fn browsing_query(&self) -> data::Query {
        let state = self.snapshot();
        // Closing the queue restores the worker's catalog asynchronously. A
        // search typed in that interval still belongs to the previous library.
        if state.page.query.screen == data::Screen::Queue && self.queue_return.is_none() {
            if let Some(query) = &self.queue_restore {
                return query.clone();
            }
        }
        state.page.query.clone()
    }
    fn ids(&self) -> Vec<String> {
        if self.selecting {
            self.selection.iter().cloned().collect()
        } else {
            self.selected
                .as_ref()
                .map(|t| vec![t.id.clone()])
                .unwrap_or_default()
        }
    }

    fn navigate(&mut self, cx: &mut Cx, screen: data::Screen) {
        self.settings_library_return = None;
        self.queue_return = None;
        self.queue_restore = None;
        self.selection.clear();
        self.selecting = false;
        self.pending_selection = None;
        self.all_selected_query = None;
        self.send(cx, data::Command::ClearSelection);
        self.full_player = false;
        self.selection_ui(cx);
        self.close_dialog(cx);
        self.send(cx, data::Command::ObserveClock(false));
        let mut query = self.browsing_query();
        query.screen = screen;
        query.search.clear();
        query.source.clear();
        self.ui.text_input(cx, ids!(search)).set_text(cx, "");
        self.send(cx, data::Command::Query(query, 0));
    }

    fn close_dialog(&mut self, cx: &mut Cx) {
        self.ui.touch_scroll(cx, ids!(panel_scroll)).stop_motion();
        cx.set_key_focus(Area::Empty);
        cx.text_ime_was_dismissed();
        if self.settings_open && self.dialog != Dialog::Settings {
            self.open(cx, Dialog::Settings);
            return;
        }
        let settings_closed = self.settings_open;
        if settings_closed {
            self.settings_open = false;
            settings::stop(&self.ui, cx);
            self.visible(cx, ids!(settings_page), false);
        }
        if self.queue_return.is_some()
            && self.dialog != Dialog::Queue
            && self.snapshot().playback.queue_count > 0
        {
            self.open(cx, Dialog::Queue);
            return;
        }
        if let Some(query) = self.queue_return.take() {
            self.queue_restore = Some(query.clone());
            self.send(cx, data::Command::Query(query, 0));
        }
        self.sheet_drag = None;
        self.sheet_offset = 0.;
        self.dialog = Dialog::None;
        self.panel_wide = None;
        self.panel_anchor = None;
        self.visible(cx, ids!(overlay), false);
        self.visible(cx, ids!(cache_loading), false);
        self.send(cx, data::Command::SourcePhotos(Vec::new()));
        if let Some(mut catalog) = self
            .ui
            .widget(cx, ids!(catalog))
            .borrow_mut::<ui::Catalog>()
        {
            catalog.refresh_style();
        }
        self.ui.text_input(cx, ids!(panel_input)).set_text(cx, "");
        self.ui.image(cx, ids!(auth_qrcode)).set_texture(cx, None);
        self.qr_url.clear();
        if settings_closed {
            self.sync_ui(cx);
            self.send(cx, data::Command::ObserveClock(self.full_player));
        }
    }

    fn selection_ui(&self, cx: &mut Cx) {
        let catalog = self.ui.widget(cx, ids!(catalog));
        if let Some(mut catalog) = catalog.borrow_mut::<ui::Catalog>() {
            catalog.selection = self.selection.clone();
            catalog.selecting = self.selecting;
            catalog.redraw(cx);
        }
        let state = self.snapshot();
        self.header_state(cx, &state);
        self.text(
            cx,
            ids!(selection_all),
            if self.all_selected_query.as_ref() == Some(&state.page.query) {
                "Deselect all"
            } else {
                "Select all"
            },
        );
        self.ui
            .button(cx, ids!(selection_all))
            .set_loading(cx, self.pending_selection.is_some());
        ui::set_icon(
            &self.ui,
            cx,
            ids!(back),
            if self.selecting {
                include_str!("../resources/close.svg")
            } else {
                include_str!("../resources/back.svg")
            },
        );
    }

    fn open_editor(&mut self, cx: &mut Cx, playlist: Option<i64>) {
        let previous = self.browsing_query();
        self.close_dialog(cx);
        self.editor = Some(PlaylistEditor {
            playlist,
            previous,
            ids: Vec::new(),
            pending: None,
            all_selected: None,
            saving: false,
            selection_revision: 1,
        });
        self.ui.text_input(cx, ids!(editor_name)).set_text(cx, "");
        self.ui.text_input(cx, ids!(editor_search)).set_text(cx, "");
        self.ui
            .widget(cx, ids!(editor_catalog))
            .portal_list(cx, ids!(list))
            .set_first_id_and_scroll(0, 0.0);
        let mut query = self.browsing_query();
        query.screen = data::Screen::PlaylistEditor(playlist);
        query.search.clear();
        query.source.clear();
        self.send(cx, data::Command::ClearSelection);
        self.send(cx, data::Command::Query(query, 0));
        self.visible(cx, ids!(playlist_editor), true);
        self.visible(cx, ids!(main), false);
        self.visible(cx, ids!(full_player), false);
        self.editor_ui(cx);
    }

    fn editor_ui(&self, cx: &mut Cx) {
        let Some(editor) = &self.editor else {
            return;
        };
        self.text(
            cx,
            ids!(editor_title),
            if editor.playlist.is_some() {
                "Add recordings"
            } else {
                "New playlist"
            },
        );
        self.text(
            cx,
            ids!(editor_save),
            if editor.playlist.is_some() {
                "Add recordings"
            } else {
                "Create playlist"
            },
        );
        self.text(
            cx,
            ids!(editor_count),
            &format!("{} selected", editor.ids.len()),
        );
        let state = self.snapshot();
        let error = if !state.error.is_empty() {
            &state.error
        } else {
            &state.selection_error
        };
        self.visible(cx, ids!(editor_error), !error.is_empty());
        self.text(cx, ids!(editor_error), error);
        let ready = matches!(state.page.query.screen, data::Screen::PlaylistEditor(_));
        self.visible(cx, ids!(editor_catalog), ready);
        self.text(
            cx,
            ids!(editor_select_all),
            if editor.all_selected.as_ref() == Some(&self.snapshot().page.query) {
                "Deselect all"
            } else {
                "Select all"
            },
        );
        self.visible(cx, ids!(editor_name_shell), editor.playlist.is_none());
        self.visible(cx, ids!(editor_playlist_name), editor.playlist.is_some());
        if let Some(id) = editor.playlist
            && let Some(playlist) = self.snapshot().playlists.iter().find(|p| p.id == id)
        {
            self.text(cx, ids!(editor_playlist_name), &playlist.name);
        }
        self.ui.text_input(cx, ids!(editor_name)).set_empty_text(
            cx,
            crate::i18n::tr(&self.snapshot().language, "Playlist name").into_owned(),
        );
        self.ui.text_input(cx, ids!(editor_search)).set_empty_text(
            cx,
            crate::i18n::tr(&self.snapshot().language, "Title or artist").into_owned(),
        );
        let name = self.ui.text_input(cx, ids!(editor_name)).text();
        for path in [ids!(editor_name), ids!(editor_search)] {
            self.ui
                .text_input(cx, path)
                .set_is_read_only(cx, editor.saving);
        }
        let valid = if editor.playlist.is_some() {
            !editor.ids.is_empty()
        } else {
            !name.trim().is_empty() && name.chars().count() <= 80
        };
        self.ui
            .widget(cx, ids!(editor_save))
            .set_disabled(cx, !valid || editor.saving);
        self.ui
            .button(cx, ids!(editor_save))
            .set_loading(cx, editor.saving);
        self.ui.widget(cx, ids!(editor_select_all)).set_disabled(
            cx,
            !ready || editor.pending.is_some() || editor.saving || state.page.total == 0,
        );
        if let Some(mut catalog) = self
            .ui
            .widget(cx, ids!(editor_catalog))
            .borrow_mut::<ui::Catalog>()
        {
            catalog.selecting = true;
            if catalog.selection_version != editor.selection_revision {
                catalog.selection = editor.ids.iter().cloned().collect();
                catalog.selection_version = editor.selection_revision;
            }
            catalog.redraw(cx);
        }
    }

    fn close_editor(&mut self, cx: &mut Cx, saved: Option<i64>) {
        let Some(editor) = self.editor.take() else {
            return;
        };
        if let Some(mut catalog) = self
            .ui
            .widget(cx, ids!(editor_catalog))
            .borrow_mut::<ui::Catalog>()
        {
            catalog.selection.clear();
            catalog.selecting = false;
            catalog.selection_version = 0;
        }
        cx.set_key_focus(Area::Empty);
        cx.text_ime_was_dismissed();
        cx.stop_timer(self.search_timer);
        self.visible(cx, ids!(playlist_editor), false);
        self.visible(cx, ids!(main), true);
        let mut query = editor.previous;
        if let Some(id) = saved {
            query.screen = data::Screen::Playlist(id);
            query.search.clear();
            query.source.clear();
        }
        self.ui
            .text_input(cx, ids!(search))
            .set_text(cx, &query.search);
        self.send(cx, data::Command::ClearSelection);
        self.send(cx, data::Command::Query(query, 0));
        self.ui.redraw(cx);
    }

    fn leave_editor(&mut self, cx: &mut Cx) {
        let Some(editor) = &self.editor else {
            return;
        };
        if editor.saving {
            return;
        }
        if !editor.ids.is_empty()
            || !self
                .ui
                .text_input(cx, ids!(editor_name))
                .text()
                .trim()
                .is_empty()
        {
            self.open(cx, Dialog::DiscardEditor);
        } else {
            self.close_editor(cx, None);
        }
    }

    fn open(&mut self, cx: &mut Cx, dialog: Dialog) {
        cx.set_key_focus(Area::Empty);
        cx.text_ime_was_dismissed();
        if dialog == Dialog::Settings {
            let entering = !self.settings_open;
            self.settings_open = true;
            self.dialog = dialog;
            self.panel_wide = None;
            self.panel_anchor = None;
            self.sheet_drag = None;
            self.sheet_offset = 0.;
            self.sync_ui(cx);
            self.configure_dialog(cx);
            if entering {
                self.send(cx, data::Command::ObserveClock(false));
                self.refresh_settings_cache(cx);
            }
            return;
        }
        if self.settings_open {
            settings::stop(&self.ui, cx);
        }
        self.panel_wide.get_or_insert(self.size.x >= 600.);
        self.panel_anchor = match &dialog {
            Dialog::Track | Dialog::PlaylistActions(_) => self.panel_anchor,
            Dialog::Speed => self.anchor(cx, ids!(speed)),
            Dialog::Order if self.dialog == Dialog::Track => self.panel_anchor,
            Dialog::Order => self.anchor(
                cx,
                if self.full_player {
                    ids!(order)
                } else {
                    ids!(library_order)
                },
            ),
            Dialog::Repeat => self.anchor(cx, ids!(repeat)),
            Dialog::Filter => self.anchor(cx, ids!(source_filter)),
            Dialog::Sort => self.anchor(cx, ids!(sort)),
            Dialog::Playlists => self
                .panel_anchor
                .or_else(|| self.anchor(cx, ids!(selection_playlist))),
            Dialog::Recipients | Dialog::Folders(true) | Dialog::Topics(..) => self
                .panel_anchor
                .or_else(|| self.anchor(cx, ids!(player_share))),
            Dialog::AddSource | Dialog::Chats | Dialog::Folders(false) => self
                .panel_anchor
                .or_else(|| self.anchor(cx, ids!(add_source))),
            _ => None,
        };
        self.dialog = dialog;
        self.ui.text_input(cx, ids!(panel_input)).set_text(cx, "");
        if self.dialog == Dialog::BookmarkThreshold {
            self.ui.text_input(cx, ids!(panel_input)).set_text(
                cx,
                &self.snapshot().settings["resume_minutes"]
                    .as_u64()
                    .unwrap_or(30)
                    .to_string(),
            );
        }
        self.ui
            .touch_scroll(cx, ids!(panel_scroll))
            .set_scroll_pos(cx, Vec2d::default());
        self.ui
            .widget(cx, ids!(choices_body))
            .portal_list(cx, ids!(list))
            .set_first_id_and_scroll(0, 0.);
        self.configure_dialog(cx);
    }

    fn refresh_settings_cache(&mut self, cx: &mut Cx) {
        let state = self.snapshot();
        if self.settings_section == settings::Section::Storage
            && state.auth.kind == "authorizationStateReady"
            && !state.cache_loading
        {
            self.send(cx, data::Command::CacheInfo);
        }
    }

    fn configure_dialog(&mut self, cx: &mut Cx) {
        let state = self.snapshot();
        let dialog = self.dialog.clone();
        if dialog == Dialog::Settings {
            self.visible(cx, ids!(overlay), false);
            settings::select(&self.ui, cx, self.settings_section);
            settings::refresh(&self.ui, cx, &state);
            self.layout(cx);
            return;
        }
        self.visible(cx, ids!(overlay), dialog != Dialog::None);
        self.visible(cx, ids!(auth_background), dialog == Dialog::Auth);
        self.visible(cx, ids!(scrim), dialog != Dialog::Auth);
        self.visible(cx, ids!(panel_title), dialog != Dialog::Auth);
        self.visible(cx, ids!(auth_heading), dialog == Dialog::Auth);
        self.visible(cx, ids!(auth_back), dialog == Dialog::Auth);
        self.visible(cx, ids!(panel_close), dialog != Dialog::Auth);
        ui::set_icon(
            &self.ui,
            cx,
            ids!(panel_close),
            if dialog == Dialog::Auth {
                include_str!("../resources/back.svg")
            } else {
                include_str!("../resources/close.svg")
            },
        );
        for path in [
            ids!(sharing_body),
            ids!(panel_cancel),
            ids!(track_body),
            ids!(choices_body),
            ids!(speed_body),
            ids!(panel_input_wrap),
            ids!(panel_message),
            ids!(panel_confirm),
            ids!(auth_actions),
            ids!(auth_mark),
            ids!(auth_qr_area),
            ids!(directory_folder),
            ids!(manage_sources),
            ids!(send_header),
            ids!(send_options),
            ids!(quick_body),
            ids!(folder_choices),
            ids!(queue_catalog),
            ids!(queue_summary),
            ids!(panel_footer),
        ] {
            self.visible(cx, path, false);
        }
        self.ui
            .text_input(cx, ids!(panel_input))
            .set_is_password(cx, false);
        self.ui
            .text_input(cx, ids!(panel_input))
            .set_is_numeric_only(cx, dialog == Dialog::BookmarkThreshold);
        self.choices.clear();
        self.ui
            .widget(cx, ids!(panel_confirm))
            .set_disabled(cx, false);
        self.ui
            .button(cx, ids!(panel_confirm))
            .set_enabled(cx, true);
        let search = matches!(dialog, Dialog::Filter | Dialog::Chats | Dialog::Recipients);
        self.visible(cx, ids!(panel_search_icon), search);
        let mut input_wrap = self.ui.widget(cx, ids!(panel_input_wrap));
        let padding = Inset {
            left: if search { 16.0 } else { 0.0 },
            right: if search { 4.0 } else { 0.0 },
            ..Default::default()
        };
        script_apply_eval!(cx,input_wrap,{padding:#(padding)});
        self.visible(cx, ids!(panel_back), dialog == Dialog::Sharing);
        self.visible(cx, ids!(panel_header), dialog != Dialog::Track);
        let title = match &dialog {
            Dialog::None => "",
            Dialog::Settings => "Settings",
            Dialog::Sharing => "Sharing audio",
            Dialog::AddSource => "Add source",
            Dialog::Queue => "Queue",
            Dialog::DiscardEditor => "Discard playlist changes?",
            Dialog::Auth => "Connect Telegram",
            Dialog::Track => "Recording",
            Dialog::Chats => "Choose chats",
            Dialog::Profile => "Open a profile",
            Dialog::Filter => "Choose source",
            Dialog::Sort => "Sort music",
            Dialog::Playlists => "Add to playlist",
            Dialog::RenamePlaylist(_) => "Rename playlist",
            Dialog::PlaylistActions(_) => "Playlist",
            Dialog::Recipients => "Send audio",
            Dialog::Folders(_) => "Chat folders",
            Dialog::Topics(..) => "Choose forum topic",
            Dialog::Delete => "Remove copy",
            Dialog::ClearCache => "Clear music cache",
            Dialog::Logout => "Sign out",
            Dialog::DeletePlaylist(_) => "Delete playlist",
            Dialog::Speed => "Speed",
            Dialog::Order => "Playback order",
            Dialog::Repeat => "Repeat",
            Dialog::BookmarkThreshold => "Bookmark threshold",
            Dialog::RemoveSource(..) => "Remove source",
        };
        self.text(cx, ids!(panel_title), title);
        match &dialog {
            Dialog::Sharing => {
                self.visible(cx, ids!(sharing_body), true);
                for (id, key, label) in [
                    (
                        ids!(settings_sharing_sender),
                        "sharing_sender",
                        "Keep original sender",
                    ),
                    (
                        ids!(settings_sharing_caption),
                        "sharing_caption",
                        "Keep original caption",
                    ),
                ] {
                    self.text(cx, id, label);
                    let checked = if state.settings[key] == true {
                        1.0
                    } else {
                        0.0
                    };
                    let mut toggle = self.ui.widget(cx, id).widget(cx, ids!(toggle));
                    script_apply_eval!(cx,toggle,{draw_bg.checked:#(checked)});
                }
            }
            Dialog::Auth => {
                self.visible(cx, ids!(auth_actions), true);
                self.visible(cx, ids!(auth_mark), true);
                let kind = state.auth.kind.as_str();
                let compact = matches!(
                    kind,
                    "authorizationStateWaitCode"
                        | "authorizationStateWaitPassword"
                        | "authorizationStateWaitEmailAddress"
                        | "authorizationStateWaitEmailCode"
                        | "authorizationStateWaitOtherDeviceConfirmation"
                );
                let mut mark = self.ui.widget(cx, ids!(auth_mark));
                let flow = if compact { Flow::right() } else { Flow::Down };
                let height = if compact { 48.0 } else { 132.0 };
                script_apply_eval!(cx,mark,{flow:#(flow) height:#(height)});
                let mut photo = self.ui.widget(cx, ids!(auth_photo));
                let size = if compact { 40.0 } else { 64.0 };
                script_apply_eval!(cx,photo,{width:#(size) height:#(size)});
                let mut name = self.ui.widget(cx, ids!(auth_name));
                let font = if compact { 18.0 } else { 21.0 };
                script_apply_eval!(cx,name,{draw_text.text_style.font_size:#(font)});
                self.text(
                    cx,
                    ids!(auth_heading),
                    match kind {
                        "authorizationStateWaitCode" => "Login code",
                        "authorizationStateWaitPassword" => "Password",
                        "authorizationStateWaitEmailAddress" => "Email address",
                        "authorizationStateWaitEmailCode" => "Email code",
                        "authorizationStateWaitOtherDeviceConfirmation" => {
                            "Confirm sign-in in Telegram"
                        }
                        _ => "Connect Telegram",
                    },
                );
                let (message,placeholder)=match kind {
                    "authorizationStateWaitCode"=>{
                        let help=match state.auth.detail.as_str(){
                            "authenticationCodeTypeTelegramMessage"=>"Enter the code sent to you in Telegram.",
                            "authenticationCodeTypeSms"|"authenticationCodeTypeSmsWord"|"authenticationCodeTypeSmsPhrase"=>"Enter the code sent to you by SMS.",
                            "authenticationCodeTypeCall"=>"Enter the code from the Telegram phone call.",
                            "authenticationCodeTypeMissedCall"=>"Enter the code from the missed call number.",
                            "authenticationCodeTypeFragment"=>"Enter the code provided by Fragment.",
                            _=>"Enter your Telegram sign-in code.",
                        };
                        (format!("{}\n{}",crate::i18n::tr(&state.language,help),state.auth.phone),"Login code")
                    },
                    "authorizationStateWaitPassword"=>{
                        let mut message=crate::i18n::tr(&state.language,"Enter your Telegram password.").into_owned();
                        if !state.auth.detail.is_empty(){message.push_str(&format!("\n{} {}",crate::i18n::tr(&state.language,"Hint:"),state.auth.detail));}
                        (message,"Password")
                    },
                    "authorizationStateWaitEmailAddress"=>("Telegram needs an email address to continue.".into(),"Email address"),
                    "authorizationStateWaitEmailCode"=>(format!("{} {}",crate::i18n::tr(&state.language,"Enter the code sent to your email:"),state.auth.detail),"Email code"),
                    "authorizationStateWaitOtherDeviceConfirmation"=>("In Telegram on your phone, open Settings → Devices → Link Desktop Device and scan this code.".into(),""),
                    "authorizationStateReady"=>("Telegram is connected.".into(),""),
                    "starting"|"authorizationStateWaitTdlibParameters"=>("Preparing a private Telegram session…".into(),""),
                    _=>("Enter your phone number with its country code. This preview uses a separate session. With the same account, playlists, favorites and library settings sync with stable Spotygram.".into(),"+1 234 567 890"),
                };
                self.visible(cx, ids!(panel_message), true);
                self.text(cx, ids!(panel_message), &message);
                self.visible(cx, ids!(panel_input_wrap), !placeholder.is_empty());
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, placeholder.to_owned());
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_is_password(cx, kind == "authorizationStateWaitPassword");
                self.visible(cx, ids!(auth_submit), !placeholder.is_empty());
                self.visible(
                    cx,
                    ids!(auth_qr),
                    kind == "authorizationStateWaitPhoneNumber",
                );
                self.visible(
                    cx,
                    ids!(auth_phone),
                    ![
                        "starting",
                        "authorizationStateWaitTdlibParameters",
                        "authorizationStateWaitPhoneNumber",
                        "authorizationStateReady",
                    ]
                    .contains(&kind),
                );
                self.visible(
                    cx,
                    ids!(auth_resend),
                    kind == "authorizationStateWaitCode" && !state.auth.next_code.is_empty(),
                );
                if kind == "authorizationStateWaitOtherDeviceConfirmation" {
                    self.visible(cx, ids!(auth_qr_area), true);
                    if self.qr_url != state.auth.detail {
                        if let Ok(code) = qrcode::QrCode::new(state.auth.detail.as_bytes()) {
                            let svg = code
                                .render::<qrcode::render::svg::Color>()
                                .min_dimensions(480, 480)
                                .build();
                            let _ = self
                                .ui
                                .image(cx, ids!(auth_qrcode))
                                .load_svg_from_data(cx, svg.as_bytes());
                            self.qr_url = state.auth.detail.clone();
                        }
                    }
                }
            }
            Dialog::Track => {
                self.visible(cx, ids!(track_body), true);
                if let Some(track) = &self.selected {
                    let connected = state.auth.kind == "authorizationStateReady";
                    let profile_action = connected
                        && !track.document
                        && (track.available || track.local() || !track.remote.is_empty());
                    let member = if state.profile_member_id == track.id {
                        state.profile_member
                    } else {
                        None
                    };
                    let inspecting = profile_action && member.is_none() && state.error.is_empty();
                    self.text(cx, ids!(track_title), &track.title);
                    self.text(
                        cx,
                        ids!(track_artist),
                        &ui::subtitle(&state.language, track),
                    );
                    ui::set_image(&self.ui, cx, ids!(track_cover.art), &track.art);
                    ui::set_cover(&self.ui, cx, ids!(track_cover), &track.title);
                    self.text(
                        cx,
                        ids!(track_favorite),
                        if track.liked {
                            "Remove from favorites"
                        } else {
                            "Add to favorites"
                        },
                    );
                    ui::set_icon(
                        &self.ui,
                        cx,
                        ids!(track_favorite),
                        if track.liked {
                            include_str!("../resources/favorite.svg")
                        } else {
                            include_str!("../resources/favorite_border.svg")
                        },
                    );
                    self.text(
                        cx,
                        ids!(track_delete),
                        if track.imported() {
                            "Delete imported copy"
                        } else {
                            "Remove downloaded copy"
                        },
                    );
                    self.text(
                        cx,
                        ids!(track_profile_member),
                        if member == Some(true) {
                            "Remove"
                        } else {
                            "To profile"
                        },
                    );
                    ui::set_icon(
                        &self.ui,
                        cx,
                        ids!(track_profile_member),
                        if member == Some(true) {
                            include_str!("../resources/person_remove.svg")
                        } else {
                            include_str!("../resources/person_add.svg")
                        },
                    );
                    self.ui
                        .button(cx, ids!(track_profile_member))
                        .set_loading(cx, inspecting);
                    if let Some(mut button) =
                        self.ui.button(cx, ids!(track_profile_member)).borrow_mut()
                    {
                        button.draw_icon.opacity = if inspecting { 0.0 } else { 1.0 };
                    }
                    self.text(cx, ids!(track_order), "Playback order");
                    self.text(
                        cx,
                        ids!(track_order.detail),
                        match state.playback.order {
                            data::Order::Normal => "In order",
                            data::Order::Reverse => "Reverse order",
                            data::Order::Shuffle => "Shuffle",
                            data::Order::Random => "Random",
                        },
                    );
                    self.visible(
                        cx,
                        ids!(track_send),
                        connected && (track.local() || track.available),
                    );
                    self.visible(
                        cx,
                        ids!(track_download),
                        track.temporary || !track.local() && track.available,
                    );
                    self.visible(cx, ids!(profile_member_wrap), profile_action);
                    self.ui.widget(cx, ids!(track_profile_member)).set_disabled(
                        cx,
                        state.profile_member_id != track.id || state.profile_member.is_none(),
                    );
                    self.visible(
                        cx,
                        ids!(track_profile_hint),
                        !track.document && connected && track.imported() && track.remote.is_empty(),
                    );
                    self.text(
                        cx,
                        ids!(track_profile_hint),
                        "This local recording will be uploaded to your Telegram profile.",
                    );
                    self.visible(cx, ids!(track_profile), track.profile != 0);
                    self.visible(cx, ids!(track_original), track.chat != 0);
                    self.visible(cx, ids!(track_delete), !track.path.is_empty());
                    self.visible(
                        cx,
                        ids!(track_remove_playlist),
                        matches!(state.page.query.screen, data::Screen::Playlist(_)),
                    );
                    let reorder = matches!(
                        state.page.query.screen,
                        data::Screen::Playlist(_) | data::Screen::Queue
                    );
                    self.visible(
                        cx,
                        ids!(track_move_up),
                        reorder && self.selected_position > 0,
                    );
                    self.visible(
                        cx,
                        ids!(track_move_down),
                        reorder && self.selected_position + 1 < state.page.total,
                    );
                    self.visible(
                        cx,
                        ids!(track_remove_queue),
                        state.page.query.screen == data::Screen::Queue,
                    );
                }
            }
            Dialog::Chats | Dialog::Recipients => {
                self.visible(cx, ids!(panel_input_wrap), true);
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, "Search Telegram chats".into());
                self.visible(cx, ids!(directory_folder), dialog == Dialog::Chats);
                let folder = state
                    .folders
                    .iter()
                    .find(|f| f.id == self.folder)
                    .map(|f| f.title.as_str())
                    .unwrap_or(if self.folder == -1 {
                        "Archive"
                    } else {
                        "All chats"
                    });
                self.text(cx, ids!(directory_folder), folder);
                if dialog == Dialog::Recipients {
                    self.visible(cx, ids!(send_header), !self.keyboard_visible);
                    self.visible(cx, ids!(send_options), true);
                    self.visible(cx, ids!(folder_choices), true);
                    if let Some(track) = &self.selected {
                        self.text(cx, ids!(send_title), &track.title);
                        self.text(cx, ids!(send_artist), &ui::subtitle(&state.language, track));
                        ui::set_cover(&self.ui, cx, ids!(send_cover), &track.title);
                        ui::set_image(&self.ui, cx, ids!(send_cover.art), &track.art);
                    }
                    let summary = format!(
                        "{} · {}",
                        crate::i18n::tr(
                            &state.language,
                            if state.settings["sharing_sender"] == true {
                                "Original sender"
                            } else {
                                "From you"
                            }
                        ),
                        crate::i18n::tr(
                            &state.language,
                            if state.settings["sharing_caption"] == true {
                                "Original caption"
                            } else {
                                "No caption"
                            }
                        )
                    );
                    self.text(cx, ids!(send_summary), &summary);
                    self.visible(cx, ids!(panel_confirm), true);
                    self.visible(cx, ids!(panel_footer), true);
                    let label = self
                        .send_target
                        .as_ref()
                        .map(|(_, _, name)| {
                            format!("{} {}", crate::i18n::tr(&state.language, "Send to"), name)
                        })
                        .unwrap_or_else(|| {
                            crate::i18n::tr(&state.language, "Choose a chat").into_owned()
                        });
                    self.text(cx, ids!(panel_confirm), &label);
                    self.ui
                        .widget(cx, ids!(panel_confirm))
                        .set_disabled(cx, self.send_target.is_none());
                    self.ui
                        .widget(cx, ids!(send_pin))
                        .set_disabled(cx, self.send_target.is_none());
                    self.quick_recipients.clear();
                    for key in [
                        format!("sharing_pins_{}", state.me),
                        format!("sharing_recent_{}", state.me),
                    ] {
                        if let Ok(rows) = serde_json::from_value::<Vec<data::Source>>(
                            state.settings[&key].clone(),
                        ) {
                            for row in rows {
                                if !self.quick_recipients.iter().any(|s| s.id == row.id) {
                                    self.quick_recipients.push(row);
                                }
                            }
                        }
                    }
                    if state.me != 0 && !self.quick_recipients.iter().any(|s| s.id == state.me) {
                        self.quick_recipients.insert(
                            0,
                            data::Source {
                                id: state.me,
                                title: crate::i18n::tr(&state.language, "Saved messages")
                                    .into_owned(),
                                kind: "Saved messages".into(),
                                ..Default::default()
                            },
                        );
                    }
                    self.quick_recipients.truncate(12);
                    self.visible(cx, ids!(quick_body), !self.quick_recipients.is_empty());
                    if self.keyboard_visible
                        || !self
                            .ui
                            .text_input(cx, ids!(panel_input))
                            .text()
                            .trim()
                            .is_empty()
                    {
                        self.visible(cx, ids!(quick_body), false);
                    }
                    if let Some(mut quick) = self
                        .ui
                        .widget(cx, ids!(quick_choices))
                        .borrow_mut::<ui::Choices>()
                    {
                        quick.rows = self
                            .quick_recipients
                            .iter()
                            .map(|source| ui::ChoiceRow {
                                title: source.title.clone(),
                                photo: source.photo.clone(),
                                key: Some((source.id, false)),
                                checked: self
                                    .send_target
                                    .as_ref()
                                    .is_some_and(|(id, _, _)| *id == source.id),
                                ..Default::default()
                            })
                            .collect();
                        quick.redraw(cx);
                    }
                    if let Some(mut folders) = self
                        .ui
                        .widget(cx, ids!(folder_choices))
                        .borrow_mut::<ui::Choices>()
                    {
                        folders.rows = std::iter::once((0, "All chats".to_owned()))
                            .chain(std::iter::once((-1, "Archive".to_owned())))
                            .chain(state.folders.iter().map(|f| (f.id, f.title.clone())))
                            .map(|(id, title)| ui::ChoiceRow {
                                title: crate::i18n::tr(&state.language, &title).into_owned(),
                                checked: self.folder == id,
                                ..Default::default()
                            })
                            .collect();
                        folders.redraw(cx);
                    }
                }
                if state.choices_scope
                    == if dialog == Dialog::Chats {
                        "chats"
                    } else {
                        "recipients"
                    }
                {
                    self.choices = state
                        .choices
                        .iter()
                        .map(|s| {
                            (
                                s.id,
                                format!(
                                    "{}{}{}",
                                    if s.selected && dialog == Dialog::Chats {
                                        "✓  "
                                    } else {
                                        ""
                                    },
                                    s.title,
                                    if s.forum { "  ›" } else { "" }
                                ),
                            )
                        })
                        .collect();
                }
            }
            Dialog::Folders(_) => {
                self.choices = vec![
                    (
                        0,
                        crate::i18n::tr(&state.language, "All chats").into_owned(),
                    ),
                    (-1, crate::i18n::tr(&state.language, "Archive").into_owned()),
                ];
                self.choices
                    .extend(state.folders.iter().map(|f| (f.id as i64, f.title.clone())));
            }
            Dialog::Topics(chat, _) => {
                if state.choices_scope == format!("topics:{chat}") {
                    self.choices = state
                        .choices
                        .iter()
                        .map(|s| (s.id, s.title.clone()))
                        .collect();
                }
            }
            Dialog::Profile => {
                self.visible(cx, ids!(panel_input_wrap), true);
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, "@username, t.me link or contact name".into());
                self.confirm_button(cx, "Open profile");
            }
            Dialog::Filter => {
                self.visible(cx, ids!(panel_input_wrap), true);
                self.ui.text_input(cx, ids!(panel_input)).set_empty_text(
                    cx,
                    crate::i18n::tr(&state.language, "Search sources").into_owned(),
                );
                let search = self
                    .ui
                    .text_input(cx, ids!(panel_input))
                    .text()
                    .trim()
                    .to_lowercase();
                self.choices.push((0, "All sources".into()));
                self.filter_sources = state
                    .sources
                    .iter()
                    .filter(|s| s.selected || s.profile && s.id == state.me)
                    .cloned()
                    .collect();
                if let Some((i, _)) = self
                    .filter_sources
                    .iter()
                    .enumerate()
                    .find(|(_, s)| s.profile && s.id == state.me)
                {
                    self.choices.push((i as i64 + 1, "My profile".into()));
                }
                for (profile, key, title) in [(true, -2, "Profiles"), (false, -3, "Chats")] {
                    let rows: Vec<_> = self
                        .filter_sources
                        .iter()
                        .enumerate()
                        .filter(|(_, s)| {
                            s.profile == profile
                                && !(s.profile && s.id == state.me)
                                && s.title.to_lowercase().contains(&search)
                        })
                        .map(|(i, s)| (i as i64 + 1, s.title.clone()))
                        .collect();
                    if !rows.is_empty() {
                        self.choices.push((key, title.into()));
                        self.choices.extend(rows);
                    }
                }
                self.visible(cx, ids!(manage_sources), true);
                self.text(cx, ids!(manage_sources), "Manage sources");
            }
            Dialog::Sort => {
                self.choices = [
                    "Newest first",
                    "Oldest first",
                    "Title",
                    "Artist",
                    "Duration",
                ]
                .iter()
                .enumerate()
                .map(|(i, s)| (i as i64, (*s).into()))
                .collect();
            }
            Dialog::Playlists => {
                self.choices = state
                    .playlists
                    .iter()
                    .map(|p| (p.id, p.name.clone()))
                    .collect();
                self.visible(cx, ids!(panel_message), state.playlists.is_empty());
                self.text(
                    cx,
                    ids!(panel_message),
                    "Create a playlist from the Playlists tab first.",
                );
            }
            Dialog::RenamePlaylist(_) => {
                self.visible(cx, ids!(panel_input_wrap), true);
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, "Playlist name".into());
                self.confirm_button(cx, "Save playlist");
            }
            Dialog::PlaylistActions(id) => {
                self.choices = vec![
                    (0, "Play playlist".into()),
                    (1, "Rename playlist".into()),
                    (2, "Delete playlist".into()),
                    (3, "Add recordings".into()),
                ];
                if let Some(p) = state.playlists.iter().find(|p| p.id == *id) {
                    self.text(cx, ids!(panel_title), &p.name);
                }
            }
            Dialog::Delete | Dialog::ClearCache | Dialog::Logout => {
                self.visible(cx, ids!(panel_message), true);
                self.text(cx,ids!(panel_message),match dialog{Dialog::Delete=>"The app-owned copy will be removed from this device. Deleting an import also removes its favorites and playlist membership from the synced library. Original external files stay intact.",Dialog::ClearCache=>"Remove downloaded Telegram music, including explicit downloads? Imported files, favorites, playlists and sign-in stay intact.",_=>"Sign out of this Rust preview? Its Telegram session and local Telegram catalog will be removed. Imported files stay intact."});
                self.confirm_button(cx, "Confirm");
            }
            Dialog::DeletePlaylist(_) => {
                self.visible(cx, ids!(panel_message), true);
                self.text(cx,ids!(panel_message),"Delete this playlist from the synced library? Its recordings will stay available.");
                self.confirm_button(cx, "Delete playlist");
            }
            Dialog::Speed => {
                self.visible(cx, ids!(speed_body), true);
                self.text(
                    cx,
                    ids!(speed_value),
                    &crate::i18n::speed(&state.language, state.playback.speed),
                );
                self.ui
                    .media_slider(cx, ids!(speed_slider))
                    .set_value(cx, state.playback.speed);
                for (id, disabled) in [
                    (
                        ids!(speed_minus),
                        state.playback.speed <= data::MIN_PLAYBACK_SPEED,
                    ),
                    (
                        ids!(speed_plus),
                        state.playback.speed >= data::MAX_PLAYBACK_SPEED,
                    ),
                ] {
                    let button = self.ui.widget(cx, id);
                    button.set_disabled(cx, disabled);
                    if let Some(mut button) = button.borrow_mut::<Button>() {
                        button.draw_icon.opacity = if disabled { 0.38 } else { 1.0 };
                    }
                }
                for (id, value) in [
                    (ids!(speed_slow), 0.5),
                    (ids!(speed_normal), 1.0),
                    (ids!(speed_fast), 1.5),
                    (ids!(speed_double), 2.0),
                    (ids!(speed_triple), 3.0),
                ] {
                    self.text(cx, id, &crate::i18n::speed(&state.language, value));
                    let mut button = self.ui.widget(cx, id);
                    let active = if (state.playback.speed - value).abs() < 0.025 {
                        1.0
                    } else {
                        0.0
                    };
                    script_apply_eval!(cx,button,{draw_bg.accent:#(active) draw_text.accent:#(active)});
                }
            }
            Dialog::Order => {
                self.choices = ["In order", "Reverse order", "Shuffle", "Random"]
                    .iter()
                    .enumerate()
                    .map(|(i, s)| (i as i64, (*s).into()))
                    .collect();
            }
            Dialog::Repeat => {
                self.choices = ["Repeat off", "Repeat all", "Repeat one"]
                    .iter()
                    .enumerate()
                    .map(|(i, s)| (i as i64, (*s).into()))
                    .collect();
            }
            Dialog::BookmarkThreshold => {
                self.visible(cx, ids!(panel_input_wrap), true);
                self.visible(cx, ids!(panel_message), true);
                self.text(cx, ids!(panel_message), "Duration in minutes (1 or more)");
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, crate::i18n::tr(&state.language, "Minutes").into_owned());
                self.confirm_button(cx, "Save threshold");
                let valid = self.threshold_minutes(cx).is_some();
                self.ui
                    .button(cx, ids!(panel_confirm))
                    .set_enabled(cx, valid);
                self.ui
                    .widget(cx, ids!(panel_confirm))
                    .set_disabled(cx, !valid);
            }
            Dialog::RemoveSource(..) => {
                self.visible(cx, ids!(panel_message), true);
                self.text(
                    cx,
                    ids!(panel_message),
                    "Disconnect this source from your library?",
                );
                self.confirm_button(cx, "Remove from library");
            }
            Dialog::AddSource => {
                self.choices = vec![(0, "Add chats".into()), (1, "Add profile".into())];
            }
            Dialog::Queue => {
                self.visible(cx, ids!(queue_catalog), true);
                self.visible(cx, ids!(queue_summary), true);
                let count = crate::i18n::tracks(&state.language, state.playback.queue_count);
                let mode = match state.playback.order {
                    data::Order::Shuffle => "Shuffle",
                    data::Order::Random => "Random",
                    data::Order::Reverse => "Reverse order",
                    _ => "In order",
                };
                self.text(
                    cx,
                    ids!(queue_summary),
                    &format!("{} · {}", count, crate::i18n::tr(&state.language, mode)),
                );
            }
            Dialog::DiscardEditor => {
                self.visible(cx, ids!(panel_message), true);
                self.text(
                    cx,
                    ids!(panel_message),
                    "The name and selected recordings have not been saved.",
                );
                self.confirm_button(cx, "Discard");
            }
            Dialog::None | Dialog::Settings => {}
        }
        let choices = self.ui.widget(cx, ids!(choices_body));
        if let Some(mut widget) = choices.borrow_mut::<ui::Choices>() {
            let selected = match dialog {
                Dialog::Sort => Some(match state.page.query.sort {
                    data::Sort::Newest => 0,
                    data::Sort::Oldest => 1,
                    data::Sort::Title => 2,
                    data::Sort::Artist => 3,
                    data::Sort::Duration => 4,
                }),
                Dialog::Order => Some(match state.playback.order {
                    data::Order::Normal => 0,
                    data::Order::Reverse => 1,
                    data::Order::Shuffle => 2,
                    data::Order::Random => 3,
                }),
                Dialog::Repeat => Some(match state.playback.repeat {
                    data::Repeat::Off => 0,
                    data::Repeat::All => 1,
                    data::Repeat::One => 2,
                }),
                Dialog::Filter => {
                    let key = if state.page.query.source.is_empty() {
                        0
                    } else {
                        self.filter_sources
                            .iter()
                            .position(|s| {
                                format!("{}:{}", if s.profile { "profile" } else { "chat" }, s.id)
                                    == state.page.query.source
                            })
                            .map(|i| i + 1)
                            .unwrap_or(0)
                    };
                    self.choices.iter().position(|(id, _)| *id == key as i64)
                }
                Dialog::Recipients => self
                    .send_target
                    .as_ref()
                    .and_then(|(chat, _, _)| self.choices.iter().position(|(id, _)| id == chat)),
                _ => None,
            };
            widget.rows = self
                .choices
                .iter()
                .enumerate()
                .map(|(index, (id, s))| {
                    let source = if matches!(dialog, Dialog::Chats | Dialog::Recipients) {
                        state.choices.get(index)
                    } else if dialog == Dialog::Filter && *id > 0 {
                        self.filter_sources.get(*id as usize - 1)
                    } else {
                        None
                    };
                    let title = if matches!(
                        dialog,
                        Dialog::Sort
                            | Dialog::Order
                            | Dialog::Repeat
                            | Dialog::PlaylistActions(_)
                            | Dialog::AddSource
                    ) || dialog == Dialog::Filter && (*id <= 0 || s == "My profile")
                    {
                        crate::i18n::tr(&state.language, s).into_owned()
                    } else {
                        s.trim_start_matches("✓  ")
                            .trim_end_matches("  ›")
                            .to_owned()
                    };
                    ui::ChoiceRow {
                        title,
                        subtitle: source
                            .map(|source| {
                                if dialog == Dialog::Filter {
                                    crate::i18n::tracks(&state.language, source.count)
                                } else {
                                    crate::i18n::tr(&state.language, &source.kind).into_owned()
                                }
                            })
                            .unwrap_or_default(),
                        photo: source.map(|s| s.photo.clone()).unwrap_or_default(),
                        key: source.map(|s| (s.id, s.profile)),
                        heading: dialog == Dialog::Filter && *id < 0,
                        library: dialog == Dialog::Filter && *id == 0,
                        checked: selected == Some(index)
                            || dialog == Dialog::Chats && source.is_some_and(|s| s.selected),
                        glass: dialog == Dialog::Filter,
                        radio: matches!(dialog, Dialog::Recipients | Dialog::Topics(..)),
                    }
                })
                .collect();
            widget.redraw(cx);
        }
        let input = self.ui.text_input(cx, ids!(panel_input));
        // Password and typed input survive language changes; only the placeholder changes.
        if let Some(mut input) = input.borrow_mut() {
            let placeholder = input.empty_text().to_owned();
            input.set_empty_text(
                cx,
                crate::i18n::tr(&state.language, &placeholder).into_owned(),
            );
        }
        let error = &state.error;
        self.visible(cx, ids!(panel_error), !error.is_empty());
        self.text(cx, ids!(panel_error), error);
        if dialog == Dialog::Auth {
            self.ui
                .widget(cx, ids!(auth_submit))
                .set_disabled(cx, state.auth.busy);
        }
        self.visible(
            cx,
            ids!(choices_body),
            !self.choices.is_empty()
                || matches!(
                    dialog,
                    Dialog::Chats | Dialog::Recipients | Dialog::Topics(..)
                ),
        );
        self.layout(cx);
    }

    fn confirm_button(&self, cx: &mut Cx, text: &str) {
        self.visible(cx, ids!(panel_footer), true);
        self.visible(cx, ids!(panel_cancel), true);
        self.visible(cx, ids!(panel_confirm), true);
        self.text(cx, ids!(panel_confirm), text);
    }

    fn threshold_minutes(&self, cx: &mut Cx) -> Option<i32> {
        self.ui
            .text_input(cx, ids!(panel_input))
            .text()
            .trim()
            .parse::<i32>()
            .ok()
            .filter(|minutes| *minutes > 0)
    }

    fn select_recipient(&mut self, cx: &mut Cx, source: &data::Source) {
        if source.forum {
            self.send(cx, data::Command::ShareTopics(source.id));
            self.open(cx, Dialog::Topics(source.id, source.title.clone()));
        } else {
            self.send_target = Some((source.id, 0, source.title.clone()));
            self.configure_dialog(cx);
        }
    }

    fn sync_ui(&mut self, cx: &mut Cx) {
        let Some(client) = &self.client else {
            return;
        };
        let state = client.snapshot.load_full();
        if state.closed {
            self.closed = true;
            cx.quit();
            return;
        }
        let old = self.state.replace(state.clone());
        if self.queue_return.is_some() && state.playback.queue_count == 0 {
            self.full_player = false;
            self.close_dialog(cx);
            self.send(cx, data::Command::ObserveClock(false));
        }
        if self.editor.as_ref().is_some_and(|editor| editor.saving) {
            if old
                .as_ref()
                .is_some_and(|old| old.playlist_save_revision != state.playlist_save_revision)
            {
                self.close_editor(cx, Some(state.playlist_saved_id));
                return;
            }
            if !state.error.is_empty() {
                if let Some(editor) = &mut self.editor {
                    editor.saving = false;
                }
                self.editor_ui(cx);
            }
        }
        if old
            .as_ref()
            .is_none_or(|old| old.selection_revision != state.selection_revision)
        {
            if let Some(editor) = &mut self.editor
                && let Some((query, add)) = editor.pending.take()
            {
                editor.selection_revision += 1;
                if query == state.selection_query && state.selection_error.is_empty() {
                    if add {
                        let mut chosen: HashSet<_> = editor.ids.iter().cloned().collect();
                        editor.ids.extend(
                            state
                                .selection_ids
                                .iter()
                                .filter(|id| chosen.insert((*id).clone()))
                                .cloned(),
                        );
                        editor.all_selected = Some(query);
                    } else {
                        let removed: HashSet<_> = state.selection_ids.iter().collect();
                        editor.ids.retain(|id| !removed.contains(id));
                        editor.all_selected = None;
                    }
                }
                self.editor_ui(cx);
            }
            if let Some((query, add)) = self.pending_selection.take() {
                if self.selecting
                    && query == state.selection_query
                    && state.selection_error.is_empty()
                {
                    if add {
                        self.selection.extend(state.selection_ids.iter().cloned());
                        self.all_selected_query = Some(query);
                    } else {
                        for id in state.selection_ids.iter() {
                            self.selection.remove(id);
                        }
                        self.all_selected_query = None;
                    }
                }
                self.selection_ui(cx);
            }
        }
        if self.sticker.is_none() && state.revision > 0 {
            self.choose_sticker(cx);
        }
        let language_changed = old
            .as_ref()
            .is_none_or(|old| old.language != state.language);
        if language_changed {
            self.localize(cx);
            self.editor_ui(cx);
        }
        let page_changed = old.as_ref().is_none_or(|old| {
            old.page.revision != state.page.revision
                || old.page.offset != state.page.offset
                || old.page.query != state.page.query
        });
        let queue_transition = state.page.query.screen == data::Screen::Queue
            || old.as_ref().is_some_and(|old| {
                old.page.query.screen == data::Screen::Queue
                    && self.queue_restore.as_ref() == Some(&state.page.query)
            });
        // The overlay has its own list. Keep the library's scroll position,
        // including the brief interval before its restored page is published.
        self.visible(
            cx,
            ids!(catalog),
            state.page.query.screen != data::Screen::Queue,
        );
        if !queue_transition
            && old
                .as_ref()
                .is_some_and(|old| old.page.query != state.page.query)
        {
            self.ui
                .widget(cx, ids!(catalog))
                .portal_list(cx, ids!(list))
                .set_first_id_and_scroll(0, 0.);
        }
        if let Some(saved) = self.settings_library_return.as_ref()
            && saved.restoring
            && saved.query == state.page.query
        {
            self.ui
                .widget(cx, ids!(catalog))
                .portal_list(cx, ids!(list))
                .set_first_id_and_scroll(saved.first, saved.scroll);
            self.ui
                .text_input(cx, ids!(search))
                .set_text(cx, &saved.query.search);
            self.full_player = saved.full_player;
            self.settings_library_return = None;
        }
        let lists_changed = old.as_ref().is_none_or(|old| {
            !Arc::ptr_eq(&old.sources, &state.sources)
                || !Arc::ptr_eq(&old.playlists, &state.playlists)
        });
        if old
            .as_ref()
            .is_none_or(|old| !Arc::ptr_eq(&old.avatar_paths, &state.avatar_paths))
        {
            self.ui.widget(cx, ids!(choices_body)).redraw(cx);
            if let Some(old) = &old {
                let retained: HashSet<_> =
                    state.avatar_paths.values().map(String::as_str).collect();
                for path in old
                    .avatar_paths
                    .values()
                    .filter(|path| !path.is_empty() && !retained.contains(path.as_str()))
                {
                    makepad_widgets::makepad_draw::evict_image_from_cache(
                        cx,
                        std::path::Path::new(path),
                    );
                }
            }
        }
        let screen = &state.page.query.screen;
        self.header_state(cx, &state);
        if old
            .as_ref()
            .is_none_or(|old| old.page.query.screen != state.page.query.screen)
        {
            ui::set_icon(
                &self.ui,
                cx,
                ids!(refresh),
                if matches!(screen, data::Screen::Playlist(_)) {
                    include_str!("../resources/more.svg")
                } else {
                    include_str!("../resources/refresh.svg")
                },
            );
        }
        let refreshing =
            state.busy && self.editor.is_none() && !self.full_player && !self.selecting;
        let source_screen = *screen == data::Screen::Sources;
        let source_top = self.size.x - self.safe.left - self.safe.right >= 600.0;
        for (path, visible) in [
            (
                ids!(refresh),
                !matches!(
                    screen,
                    data::Screen::Sources | data::Screen::Playlists | data::Screen::Playlist(_)
                ),
            ),
            (ids!(source_refresh), source_screen && !source_top),
            (ids!(source_refresh_top), source_screen && source_top),
        ] {
            let loading = refreshing && visible;
            let button = self.ui.button(cx, path);
            button.set_loading(cx, loading);
            if let Some(mut button) = button.borrow_mut() {
                button.draw_icon.opacity = if loading { 0.0 } else { 1.0 };
            }
        }
        if page_changed || language_changed {
            self.layout(cx);
            self.editor_ui(cx);
            let placeholder = match screen {
                data::Screen::Profile(_) => "Search profile",
                data::Screen::Favorites => "Search favorites",
                data::Screen::Playlists => "Find a playlist",
                _ => "Search music",
            };
            self.ui.text_input(cx, ids!(search)).set_empty_text(
                cx,
                crate::i18n::tr(&state.language, placeholder).into_owned(),
            );
            if let Some(source) = state.sources.iter().find(|s| {
                format!("{}:{}", if s.profile { "profile" } else { "chat" }, s.id)
                    == state.page.query.source
            }) {
                self.ui
                    .widget(cx, ids!(source_filter))
                    .set_text(cx, &source.title);
            } else {
                self.text(cx, ids!(source_filter), "All sources");
            }
        }
        self.visible(
            cx,
            ids!(header),
            !self.keyboard_visible && !matches!(screen, data::Screen::Profile(_)),
        );
        self.visible(
            cx,
            ids!(search_shell),
            !matches!(screen, data::Screen::Sources)
                && (!matches!(screen, data::Screen::Playlists) || !state.playlists.is_empty()),
        );
        if let data::Screen::Profile(id) = screen {
            if let Some(source) = state.sources.iter().find(|s| s.profile && s.id == *id) {
                let username = if source.username.is_empty() {
                    crate::i18n::tr(&state.language, "Profile music").into_owned()
                } else {
                    format!("@{}", source.username.trim_start_matches('@'))
                };
                self.ui
                    .widget(cx, ids!(profile_username))
                    .set_text(cx, &username);
                self.text(
                    cx,
                    ids!(profile_count),
                    &format!(
                        "{} · {}",
                        crate::i18n::tr(&state.language, "Profile music in Telegram"),
                        crate::i18n::tracks(&state.language, source.count)
                    ),
                );
                self.ui
                    .label(cx, ids!(profile_avatar.initials))
                    .set_text(cx, &ui::initials(&source.title));
                self.visible(cx, ids!(profile_connect), !source.selected);
                self.visible(cx, ids!(profile_disconnect), source.selected);
                ui::set_image(&self.ui, cx, ids!(profile_avatar.art), &source.photo);
            }
        }
        self.visible(
            cx,
            ids!(collection_controls),
            *screen == data::Screen::Sources,
        );
        let active_tab = match screen {
            data::Screen::Favorites => 1,
            data::Screen::Playlists | data::Screen::Playlist(_) => 2,
            data::Screen::Sources | data::Screen::Profile(_) => 3,
            _ => 0,
        };
        if old.as_ref().is_none_or(|s| {
            s.page.query.screen != *screen || s.settings["theme"] != state.settings["theme"]
        }) {
            let light = state.settings["theme"] == "light";
            let accents = if light {
                [
                    vec4(8. / 255., 109. / 255., 121. / 255., 1.),
                    vec4(174. / 255., 68. / 255., 80. / 255., 1.),
                    vec4(120. / 255., 90. / 255., 26. / 255., 1.),
                    vec4(39. / 255., 115. / 255., 71. / 255., 1.),
                ]
            } else {
                [
                    vec4(88. / 255., 201. / 255., 210. / 255., 1.),
                    vec4(240. / 255., 113. / 255., 117. / 255., 1.),
                    vec4(233. / 255., 217. / 255., 108. / 255., 1.),
                    vec4(114. / 255., 201. / 255., 135. / 255., 1.),
                ]
            };
            for (index, paths) in [
                [ids!(nav_music), ids!(rail_music)],
                [ids!(nav_favorites), ids!(rail_favorites)],
                [ids!(nav_playlists), ids!(rail_playlists)],
                [ids!(nav_sources), ids!(rail_sources)],
            ]
            .iter()
            .enumerate()
            {
                let color = if index == active_tab {
                    accents[index]
                } else if light {
                    vec4(0.349, 0.396, 0.459, 1.)
                } else {
                    vec4(0.690, 0.722, 0.765, 1.)
                };
                for path in paths {
                    let mut widget = self.ui.widget(cx, *path);
                    script_apply_eval!(cx,widget,{draw_bg.active:0.0 draw_text.color:#(color)});
                    if let Some(mut button) = widget.borrow_mut::<Button>() {
                        button.draw_icon.color = color;
                    }
                }
            }
            for path in [ids!(nav_lens), ids!(rail_lens)] {
                if let Some(mut lens) = self
                    .ui
                    .widget(cx, path)
                    .borrow_mut::<crate::design::NavLens>()
                {
                    lens.set_destination(cx, active_tab, accents[active_tab]);
                }
            }
        }
        let notice = if !state.error.is_empty() {
            &state.error
        } else {
            &state.status
        };
        self.visible(cx, ids!(notice), !notice.is_empty());
        self.text(cx, ids!(notice), notice);
        let playback = &state.playback;
        let player_notice = state.error.as_str();
        self.visible(cx, ids!(player_notice), !player_notice.is_empty());
        self.text(cx, ids!(player_notice), player_notice);
        let active = playback.track.is_some();
        self.visible(cx, ids!(settings_page), self.settings_open);
        self.visible(cx, ids!(player_dock), active && !self.settings_open);
        self.visible(
            cx,
            ids!(full_player),
            !self.settings_open && self.editor.is_none() && self.full_player && active,
        );
        if self.settings_open || self.editor.is_some() || !self.full_player || !active {
            self.ui.touch_scroll(cx, ids!(player_columns)).stop_motion();
        }
        self.visible(
            cx,
            ids!(main),
            !self.settings_open && self.editor.is_none() && (!self.full_player || !active),
        );
        self.visible(
            cx,
            ids!(playlist_editor),
            !self.settings_open && self.editor.is_some(),
        );
        for path in [ids!(mini_play), ids!(previous), ids!(next)] {
            self.visible(cx, path, active);
        }
        self.visible(cx, ids!(previous), active);
        if let Some(track) = &playback.track {
            if old
                .as_ref()
                .and_then(|s| s.playback.track.as_ref())
                .is_none_or(|t| {
                    t.id != track.id || t.path != track.path || t.temporary != track.temporary
                })
            {
                let local = !track.path.is_empty();
                self.text(
                    cx,
                    ids!(player_save),
                    if track.temporary {
                        "Keep on device"
                    } else if local {
                        "On device"
                    } else {
                        "Download"
                    },
                );
                self.ui
                    .widget(cx, ids!(player_save))
                    .set_disabled(cx, local && !track.temporary);
                let size = if local && !track.temporary { 0.0 } else { 20.0 };
                let mut button = self.ui.widget(cx, ids!(player_save));
                let icon_walk = Walk::fixed(size, size);
                script_apply_eval!(cx,button,{icon_walk:#(icon_walk)});
            }
            self.text(
                cx,
                ids!(playing_source),
                if track.imported() {
                    "From device"
                } else {
                    &track.source
                },
            );
            self.visible(
                cx,
                ids!(player_share),
                state.auth.kind == "authorizationStateReady",
            );
            if old
                .as_ref()
                .and_then(|s| s.playback.track.as_ref())
                .is_none_or(|t| t.id != track.id || t.liked != track.liked)
            {
                ui::set_icon(
                    &self.ui,
                    cx,
                    ids!(player_favorite),
                    if track.liked {
                        include_str!("../resources/favorite.svg")
                    } else {
                        include_str!("../resources/favorite_border.svg")
                    },
                );
                if let Some(mut button) = self.ui.button(cx, ids!(player_favorite)).borrow_mut() {
                    button.draw_icon.color = if track.liked {
                        if state.settings["theme"] == "light" {
                            vec4(0.72, 0.278, 0.314, 1.)
                        } else {
                            vec4(1.0, 0.635, 0.635, 1.0)
                        }
                    } else if state.settings["theme"] == "light" {
                        vec4(0.114, 0.188, 0.196, 1.)
                    } else {
                        vec4(0.945, 0.965, 0.953, 1.)
                    };
                }
            }
            for id in [ids!(mini_title), ids!(player_title)] {
                self.text(cx, id, &track.title);
            }
            self.text(
                cx,
                ids!(player_artist),
                &if playback.buffering {
                    crate::i18n::tr(&state.language, "Loading audio…")
                } else {
                    ui::subtitle(&state.language, track)
                },
            );
            self.text(
                cx,
                ids!(mini_artist),
                &if playback.buffering {
                    crate::i18n::tr(&state.language, "Loading audio…")
                } else {
                    ui::subtitle(&state.language, track)
                },
            );
            if old.as_ref().is_none_or(|s| {
                s.playback.requested != playback.requested
                    || s.playback.buffering != playback.buffering
            }) {
                for id in [ids!(mini_play), ids!(player_play)] {
                    ui::set_icon(
                        &self.ui,
                        cx,
                        id,
                        if playback.requested {
                            include_str!("../resources/pause.svg")
                        } else {
                            include_str!("../resources/play.svg")
                        },
                    );
                    let button = self.ui.button(cx, id);
                    // Loading buttons reject input in Makepad. Playback controls must
                    // still accept Pause while a source/decoder is preparing.
                    button.set_loading(cx, false);
                    if let Some(mut button) = button.borrow_mut() {
                        button.draw_icon.opacity = 1.0;
                    }
                }
            }
            self.text(cx, ids!(elapsed), &data::seconds(playback.position));
            self.text(cx, ids!(duration), &data::seconds(playback.duration));
            self.text(
                cx,
                ids!(speed),
                &crate::i18n::speed(&state.language, playback.speed),
            );
            self.text(
                cx,
                ids!(mini_speed),
                &crate::i18n::speed(&state.language, playback.speed),
            );
            self.visible(cx, ids!(mini_speed), (playback.speed - 1.).abs() > 0.001);
            if old
                .as_ref()
                .is_none_or(|s| s.playback.speed != playback.speed)
            {
                let changed = (playback.speed - 1.).abs() > 0.001;
                let accent = if changed { 1.0 } else { 0.0 };
                let light = state.settings["theme"] == "light";
                let color = match (changed, light) {
                    (true, true) => vec4(0.075, 0.482, 0.541, 1.),
                    (true, false) => vec4(0.541, 0.867, 0.902, 1.),
                    (false, true) => vec4(0.114, 0.188, 0.196, 1.),
                    (false, false) => vec4(0.945, 0.965, 0.953, 1.),
                };
                let mut button = self.ui.widget(cx, ids!(speed));
                script_apply_eval!(cx, button, {
                    draw_bg.accent:#(accent)
                    draw_text +:{color:#(color) color_hover:#(color) color_down:#(color) color_focus:#(color)}
                });
                self.layout(cx);
            }
            self.text(cx, ids!(volume_label), &format!("{:.0}%", playback.volume));
            if old
                .as_ref()
                .is_none_or(|s| s.playback.muted != playback.muted)
            {
                ui::set_icon(
                    &self.ui,
                    cx,
                    ids!(mute),
                    if playback.muted {
                        include_str!("../resources/mute.svg")
                    } else {
                        include_str!("../resources/volume.svg")
                    },
                );
            }
            if old
                .as_ref()
                .is_none_or(|s| s.playback.repeat != playback.repeat)
            {
                ui::set_icon(
                    &self.ui,
                    cx,
                    ids!(repeat),
                    if playback.repeat == data::Repeat::One {
                        include_str!("../resources/repeat_one.svg")
                    } else {
                        include_str!("../resources/repeat.svg")
                    },
                );
            }
            if playback.duration > 0. {
                self.ui
                    .media_slider(cx, ids!(seek))
                    .set_value(cx, (playback.position / playback.duration).clamp(0., 1.));
            }
            self.ui
                .media_slider(cx, ids!(volume))
                .set_value(cx, playback.volume);
            if old
                .as_ref()
                .and_then(|s| s.playback.track.as_ref())
                .is_none_or(|t| t.id != track.id || t.art != track.art)
            {
                ui::set_image(&self.ui, cx, ids!(mini_cover.art), &track.art);
                ui::set_image(&self.ui, cx, ids!(player_art.art), &track.art);
                ui::set_image(&self.ui, cx, ids!(player_reflection.art), &track.art);
                ui::set_cover(&self.ui, cx, ids!(mini_cover), &track.title);
                ui::set_cover(&self.ui, cx, ids!(player_art), &track.title);
                ui::set_cover(&self.ui, cx, ids!(player_reflection), &track.title);
            }
        }
        if old
            .as_ref()
            .is_none_or(|old| old.playback.order != state.playback.order)
        {
            let svg = match state.playback.order {
                data::Order::Shuffle => include_str!("../resources/shuffle.svg"),
                data::Order::Random => include_str!("../resources/dice.svg"),
                _ => include_str!("../resources/order.svg"),
            };
            for path in [ids!(order), ids!(library_order)] {
                ui::set_icon(&self.ui, cx, path, svg);
            }
        }
        let dark = state.settings["theme"] != "light";
        if self.dark != Some(dark) {
            self.dark = Some(dark);
            let theme = if dark {
                BaseTheme::Dark
            } else {
                BaseTheme::Light
            };
            if base_theme(cx) != theme {
                set_base_theme(cx, theme);
                cx.request_style_reload();
            }
            cx.set_system_bar_appearance(if dark {
                SystemBarAppearance::LightIcons
            } else {
                SystemBarAppearance::DarkIcons
            });
        }
        if page_changed || lists_changed {
            let mut images: HashSet<String> = state
                .page
                .tracks
                .iter()
                .filter(|t| !t.art.is_empty())
                .map(|t| t.art.clone())
                .collect();
            if let Some(track) = &state.playback.track {
                if !track.art.is_empty() {
                    images.insert(track.art.clone());
                }
            }
            if let Some(track) = &self.selected {
                if !track.art.is_empty() {
                    images.insert(track.art.clone());
                }
            }
            if *screen == data::Screen::Sources {
                images.extend(
                    state
                        .sources
                        .iter()
                        .filter(|s| !s.photo.is_empty())
                        .map(|s| s.photo.clone()),
                );
            }
            for path in self.image_paths.difference(&images) {
                makepad_widgets::makepad_draw::evict_image_from_cache(
                    cx,
                    std::path::Path::new(path),
                );
            }
            self.image_paths = images;
            self.ui.widget(cx, ids!(catalog)).redraw(cx);
        }
        let mut selected_changed = false;
        if let Some(selected) = &self.selected {
            if let Some(track) = state
                .page
                .tracks
                .iter()
                .find(|track| track.id == selected.id)
                .or_else(|| {
                    state
                        .playback
                        .track
                        .as_ref()
                        .filter(|track| track.id == selected.id)
                })
            {
                if track != selected {
                    self.selected = Some(track.clone());
                    selected_changed = true;
                }
            }
        }
        if self.dialog != Dialog::None {
            let reconfigure = old.as_ref().is_none_or(|s| match self.dialog {
                Dialog::Sharing => s.settings != state.settings,
                Dialog::Auth => s.auth != state.auth || s.error != state.error,
                Dialog::Track => {
                    selected_changed
                        || s.profile_member != state.profile_member
                        || s.profile_member_id != state.profile_member_id
                }
                Dialog::Speed => s.playback.speed != state.playback.speed,
                Dialog::Chats | Dialog::Recipients | Dialog::Topics(..) => {
                    !Arc::ptr_eq(&s.choices, &state.choices)
                        || s.choices_scope != state.choices_scope
                        || s.settings!=state.settings
                }
                Dialog::Queue => s.page.revision!=state.page.revision
                    || s.page.total!=state.page.total
                    || s.playback.queue_count!=state.playback.queue_count
                    || s.playback.order!=state.playback.order,
                _ => false,
            } || s.error!=state.error || s.language!=state.language);
            if reconfigure {
                self.configure_dialog(cx);
                if self.dialog == Dialog::Auth
                    && old
                        .as_ref()
                        .is_some_and(|old| old.auth.kind != state.auth.kind)
                {
                    self.ui
                        .touch_scroll(cx, ids!(panel_scroll))
                        .set_scroll_pos(cx, Vec2d::default());
                }
            }
            if self.dialog == Dialog::Auth && state.auth.kind == "authorizationStateReady" {
                self.close_dialog(cx);
            }
        }
        if self.settings_open
            && old.as_ref().is_none_or(|s| {
                s.settings != state.settings
                    || s.language != state.language
                    || s.auth != state.auth
                    || s.storage_bytes != state.storage_bytes
                    || s.storage_count != state.storage_count
                    || s.downloads != state.downloads
                    || s.transfer_progress != state.transfer_progress
                    || s.sync != state.sync
                    || s.cache_size != state.cache_size
                    || s.cache_loading != state.cache_loading
                    || s.cache_error != state.cache_error
                    || s.error != state.error
                    || s.status != state.status
            })
        {
            settings::refresh(&self.ui, cx, &state);
        }
    }

    fn localize(&self, cx: &mut Cx) {
        for (path, text) in [
            (ids!(panel_cancel).as_slice(), "Cancel"),
            (ids!(sharing_help), "Sender and original caption"),
            (ids!(playing_label), "PLAYING FROM"),
            (ids!(rail_music), "Music"),
            (ids!(nav_music), "Music"),
            (ids!(rail_favorites), "Favorites"),
            (ids!(nav_favorites), "Favorites"),
            (ids!(rail_playlists), "Playlists"),
            (ids!(nav_playlists), "Playlists"),
            (ids!(rail_sources), "Sources"),
            (ids!(nav_sources), "Sources"),
            (ids!(source_filter), "All sources"),
            (ids!(play_all), "Play all"),
            (ids!(selection_all), "Select all"),
            (ids!(quick_label), "Quick access"),
            (ids!(add_source), "Add source"),
            (ids!(add_source_top), "Add source"),
            (ids!(profile_connect), "Add to library"),
            (ids!(profile_disconnect), "In library"),
            (ids!(queue), "Queue"),
            (ids!(player_save), "Save on device"),
            (ids!(auth_submit), "Continue"),
            (ids!(auth_qr), "Sign in with QR code"),
            (ids!(auth_phone), "Use another phone number"),
            (ids!(auth_resend), "Resend code"),
            (ids!(track_playlist), "Add to playlist"),
            (ids!(track_queue), "Add to queue"),
            (ids!(track_download), "Save on device"),
            (ids!(track_send), "Send to Telegram chat"),
            (ids!(track_profile), "Open Telegram profile"),
            (ids!(track_original), "Open original message"),
            (ids!(track_remove_playlist), "Remove from this playlist"),
            (ids!(track_move_up), "Move up"),
            (ids!(track_move_down), "Move down"),
            (ids!(track_remove_queue), "Remove from queue"),
        ] {
            self.text(cx, path, text);
        }
        self.ui.text_input(cx, ids!(search)).set_empty_text(
            cx,
            crate::i18n::tr(&self.snapshot().language, "Search music").into_owned(),
        );
    }

    fn layout(&mut self, cx: &mut Cx) {
        let mut root_widget = self.ui.widget(cx, ids!(root));
        let padding = Inset {
            bottom: self.safe.bottom.max(self.keyboard_height),
            ..self.safe
        };
        script_apply_eval!(cx,root_widget,{padding:#(padding)});
        self.visible(cx, ids!(dock), !self.keyboard_visible);
        self.visible(
            cx,
            ids!(header),
            !self.keyboard_visible
                && !matches!(self.snapshot().page.query.screen, data::Screen::Profile(_)),
        );
        if self.keyboard_visible {
            self.visible(cx, ids!(auth_mark), false);
        }
        let root = self.ui.widget(cx, ids!(root)).area();
        let bounds = if root.is_valid(cx) {
            root.rect(cx)
        } else {
            Rect {
                pos: Vec2d::default(),
                size: self.size,
            }
        };
        let width = (self.size.x - self.safe.left - self.safe.right).max(280.);
        if self.settings_open {
            settings::layout(
                &self.ui,
                cx,
                width,
                self.size.y - self.safe.top - self.safe.bottom,
            );
        }
        let height = (self.size.y
            - bounds.pos.y
            - self.safe.top
            - self.safe.bottom.max(self.keyboard_height))
        .max(260.);
        let wide = width >= 840.;
        let sources = self.snapshot().page.query.screen == data::Screen::Sources;
        self.visible(cx, ids!(add_source_top), sources && width >= 600.);
        self.visible(cx, ids!(source_refresh_top), sources && width >= 600.);
        self.visible(cx, ids!(collection_controls), sources && width < 600.);
        self.visible(cx, ids!(rail), wide);
        self.visible(cx, ids!(bottom_nav), !wide);
        self.visible(cx, ids!(brand), true);
        self.visible(cx, ids!(volume_row), cfg!(not(target_os = "android")));
        self.visible(cx, ids!(previous), self.snapshot().playback.track.is_some());
        let artist_width = ((width - if wide { 96.0 } else { 0.0 }).min(720.0)
            - 233.0
            - if (self.snapshot().playback.speed - 1.0).abs() > 0.001 {
                44.0
            } else {
                0.0
            })
        .max(24.0);
        let mut mini_artist = self.ui.widget(cx, ids!(mini_artist));
        script_apply_eval!(cx,mini_artist,{max_width:#(artist_width)});
        let mut panel = self.ui.widget(cx, ids!(panel));
        let alert = matches!(
            self.dialog,
            Dialog::Delete
                | Dialog::DeletePlaylist(_)
                | Dialog::ClearCache
                | Dialog::Logout
                | Dialog::RemoveSource(..)
                | Dialog::DiscardEditor
                | Dialog::RenamePlaylist(_)
                | Dialog::BookmarkThreshold
        );
        let wide_panel = alert || self.panel_wide.unwrap_or(width >= 600.);
        let auth = self.dialog == Dialog::Auth;
        let maximum = match self.dialog {
            Dialog::Settings | Dialog::Sharing | Dialog::Queue => 640.0,
            Dialog::Track => 380.0,
            Dialog::Speed => 360.0,
            Dialog::Sort | Dialog::Order | Dialog::Repeat => 320.0,
            Dialog::Filter | Dialog::Chats | Dialog::Recipients | Dialog::Playlists => 440.0,
            _ => 520.0,
        };
        let panel_width = if auth {
            width.min(480.)
        } else if wide_panel {
            (width - 48.).clamp(240., maximum)
        } else {
            width
        };
        let opacity = if auth { 0.0 } else { 1.0 };
        let inset = if auth {
            24.0
        } else if self.dialog == Dialog::Track {
            16.0
        } else {
            20.0
        };
        let panel_padding = Inset {
            left: inset,
            right: inset,
            top: if self.dialog == Dialog::Speed {
                8.0
            } else {
                inset
            },
            bottom: if self.dialog == Dialog::Speed {
                16.0
            } else {
                inset
            },
        };
        let speed_panel = self.dialog == Dialog::Speed;
        let mut title = self.ui.widget(cx, ids!(panel_title));
        let title_height = if speed_panel {
            Size::fit()
        } else {
            Size::fill()
        };
        let title_lines: usize = if speed_panel { 1 } else { 2 };
        let title_size = if speed_panel { 13.5 } else { 15.0 };
        script_apply_eval!(cx,title,{height:#(title_height) max_lines:#(title_lines) draw_text.text_style.font_size:#(title_size)});
        let spacing = if speed_panel { 4.0 } else { 10.0 };
        let relief = if speed_panel { 0.40 } else { 1.0 };
        let diffusion = if speed_panel {
            if self.dark == Some(true) { 0.38 } else { 0.58 }
        } else if self.dark == Some(true) {
            0.38
        } else {
            0.32
        };
        script_apply_eval!(cx,panel,{width:#(panel_width) padding:#(panel_padding) spacing:#(spacing) draw_bg +:{layer_opacity:#(opacity) relief:#(relief) diffusion:#(diffusion)}});
        if self.dialog == Dialog::Track {
            let height = if wide_panel { 48.0 } else { 56.0 };
            let font = if wide_panel { 10.5 } else { 12.0 };
            let icon_size = if wide_panel { 20.0 } else { 24.0 };
            let icon_walk = Walk::fixed(icon_size, icon_size);
            let padding = Inset {
                left: 8.0,
                right: 8.0,
                top: if wide_panel { 8.0 } else { 12.0 },
                bottom: if wide_panel { 8.0 } else { 12.0 },
            };
            for path in [
                ids!(track_send),
                ids!(track_favorite),
                ids!(track_playlist),
                ids!(track_queue),
                ids!(track_move_up),
                ids!(track_move_down),
                ids!(track_remove_queue),
                ids!(track_download),
                ids!(track_profile),
                ids!(track_original),
                ids!(track_remove_playlist),
                ids!(track_delete),
            ] {
                let mut button = self.ui.widget(cx, path);
                script_apply_eval!(cx,button,{height:#(height) padding:#(padding) spacing:18 icon_walk:#(icon_walk) draw_text.text_style.font_size:#(font)});
            }
            let mut order = self.ui.widget(cx, ids!(track_order));
            script_apply_eval!(cx,order,{row.padding:#(padding) row.spacing:18 row.labels.spacing:0 row.labels.title.draw_text.text_style.font_size:#(font)});
        }
        // GlassLayer repeats its layout around the overlay. Position the panel
        // in its ordinary child view so anchor and drag insets are applied once.
        let mut layer = self.ui.widget(cx, ids!(panel_position));
        let mut align = Align {
            x: 0.5,
            y: if auth {
                0.0
            } else if wide_panel {
                0.5
            } else {
                1.0
            },
        };
        let mut padding = Inset {
            bottom: -self.sheet_offset,
            ..Default::default()
        };
        let mut anchor_space = None;
        if wide_panel && !auth {
            if let Some(anchor) = self.panel_anchor {
                let origin = bounds.pos
                    + Vec2d {
                        x: self.safe.left,
                        y: self.safe.top,
                    };
                let left = (anchor.pos.x - origin.x).clamp(0., width);
                let right = (anchor.pos.x + anchor.size.x - origin.x).clamp(0., width);
                let top = (anchor.pos.y - origin.y).clamp(0., height);
                let bottom = (anchor.pos.y + anchor.size.y - origin.y).clamp(0., height);
                let horizontal_room = (width - panel_width - 8.).max(8.);
                if (left + right) * 0.5 > width * 0.5 {
                    align.x = 1.0;
                    padding.right = (width - right).clamp(8., horizontal_room);
                } else {
                    align.x = 0.0;
                    padding.left = left.clamp(8., horizontal_room);
                }
                if (top + bottom) * 0.5 > height * 0.5 {
                    align.y = 1.0;
                    padding.bottom = (height - top + 8.).max(8.);
                    anchor_space = Some(top - 8.);
                } else {
                    align.y = 0.0;
                    padding.top = (bottom + 8.).max(8.);
                    anchor_space = Some(height - bottom - 8.);
                }
            }
        }
        script_apply_eval!(cx,layer,{align:#(align) padding:#(padding)});
        self.visible(cx, ids!(drag_handle), !wide_panel && !auth);
        let mut panel_scroll = self.ui.widget(cx, ids!(panel_scroll));
        let mut chrome = panel_padding.top + panel_padding.bottom;
        for (path, size) in [
            (ids!(drag_handle), 16.0),
            (ids!(panel_header), 48.0),
            (ids!(panel_error), 60.0),
            (ids!(manage_sources), 52.0),
            (ids!(send_options), 48.0),
            (ids!(panel_footer), 48.0),
        ] {
            if self.ui.widget(cx, path).visible() {
                chrome += size + spacing;
            }
        }
        let available = (height - if wide_panel { 48.0 } else { 0.0 } - chrome).max(56.);
        let mut available = match self.dialog {
            Dialog::Settings | Dialog::Sharing | Dialog::Track => {
                if wide_panel {
                    available.min(580.)
                } else {
                    available
                }
            }
            Dialog::Chats
            | Dialog::Recipients
            | Dialog::Topics(..)
            | Dialog::Filter
            | Dialog::Queue => available.min(if wide_panel { 380. } else { height * 0.65 }),
            Dialog::Auth => available.min(560.),
            Dialog::Speed => available.min(168.),
            _ => available.min(260.),
        };
        if let Some(space) = anchor_space {
            available = available.min((space - 110.).max(60.));
        }
        // Keep the panel header and confirmation outside the scrolling viewport.
        let fit = Size::fit();
        script_apply_eval!(cx,panel_scroll,{height:#(fit) max_height:#(available)});
        let reserved = if matches!(
            self.dialog,
            Dialog::Chats | Dialog::Recipients | Dialog::Filter
        ) {
            if self.dialog == Dialog::Recipients {
                240.0
            } else {
                112.0
            }
        } else {
            0.0
        };
        let choices_height =
            (self.choices.len().max(1) as f64 * 56.).min((available - reserved).max(56.));
        let mut choices = self.ui.widget(cx, ids!(choices_body));
        script_apply_eval!(cx,choices,{height:#(choices_height)});
        let mut queue = self.ui.widget(cx, ids!(queue_catalog));
        let queue_height = (available - 30.0)
            .max(64.0)
            .min((self.snapshot().page.total.max(1) * 64 + 16) as f64);
        script_apply_eval!(cx,queue,{height:#(queue_height)});
        let mut columns = self.ui.widget(cx, ids!(player_columns));
        let wide_player = width >= 600. && width > height;
        let flow = if wide_player {
            Flow::right()
        } else {
            Flow::Down
        };
        script_apply_eval!(cx,columns,{flow:#(flow)});
        let mut art = self.ui.widget(cx, ids!(player_art));
        let size = if wide_player {
            (width * 0.43).min(height * 0.70).min(340.)
        } else {
            (width - 48.)
                .min(height * 0.42)
                .min((height - 390. - if cfg!(target_os = "android") { 0. } else { 48. }).max(96.))
                .min(340.)
        };
        script_apply_eval!(cx,art,{width:#(size) height:#(size)});
        let mut note = self.ui.widget(cx, ids!(player_art.note));
        let note_size = size * 0.42;
        script_apply_eval!(cx,note,{width:#(note_size) height:#(note_size)});
        let mut fields = self.ui.widget(cx, ids!(editor_fields));
        let flow = if height < 480.0 || self.keyboard_visible {
            Flow::right()
        } else {
            Flow::Down
        };
        script_apply_eval!(cx,fields,{flow:#(flow)});
    }

    fn begin_quit(&mut self, cx: &mut Cx) {
        if !self.quitting {
            self.quitting = true;
            self.send(cx, data::Command::Quit);
            self.text(cx, ids!(notice), "Saving and closing…");
            self.visible(cx, ids!(notice), true);
        }
    }

    fn request_close(&mut self, cx: &mut Cx) {
        if self.closed {
            return;
        }
        let state = self.snapshot();
        if state.playback.requested || state.downloads > 0 || state.busy {
            self.ui.window(cx, ids!(main_window)).minimize(cx);
            self.send(cx, data::Command::Visible(false));
        } else {
            self.begin_quit(cx);
        }
    }

    fn confirm(&mut self, cx: &mut Cx) {
        let state = self.snapshot();
        let input = self.ui.text_input(cx, ids!(panel_input)).text();
        match self.dialog.clone() {
            Dialog::Profile => self.send(cx, data::Command::OpenProfile(input)),
            Dialog::RenamePlaylist(id) => self.send(cx, data::Command::PlaylistRename(id, input)),
            Dialog::Recipients => {
                let Some((recipient, topic, _)) = self.send_target.clone() else {
                    return;
                };
                if let Some(track) = &self.selected {
                    self.send(
                        cx,
                        data::Command::Send {
                            track: track.id.clone(),
                            recipient,
                            topic,
                            sender: state.settings["sharing_sender"] == true,
                            caption: state.settings["sharing_caption"] == true,
                        },
                    );
                }
            }
            Dialog::DiscardEditor => self.close_editor(cx, None),
            Dialog::Delete => self.send(cx, data::Command::Delete(self.ids())),
            Dialog::DeletePlaylist(id) => self.send(cx, data::Command::PlaylistDelete(id)),
            Dialog::ClearCache => self.send(cx, data::Command::ClearCache),
            Dialog::Logout => self.send(cx, data::Command::Logout),
            Dialog::BookmarkThreshold => {
                let Some(minutes) = self.threshold_minutes(cx) else {
                    return;
                };
                self.send(
                    cx,
                    data::Command::Setting("resume_minutes".into(), json!(minutes)),
                );
            }
            Dialog::RemoveSource(id, profile) => {
                if let Some(source) = state
                    .sources
                    .iter()
                    .find(|s| s.id == id && s.profile == profile)
                {
                    self.send(cx, data::Command::ConnectSource(source.clone(), false));
                }
            }
            _ => {}
        }
        self.close_dialog(cx);
    }

    fn choose(&mut self, cx: &mut Cx, index: usize) {
        let Some((id, title)) = self.choices.get(index).cloned() else {
            return;
        };
        let state = self.snapshot();
        match self.dialog.clone() {
            Dialog::AddSource => {
                if id == 0 {
                    self.send(cx, data::Command::Discover(String::new(), self.folder));
                    self.open(cx, Dialog::Chats);
                } else {
                    self.open(cx, Dialog::Profile);
                }
                return;
            }
            Dialog::Chats => {
                if let Some(source) = state.choices.iter().find(|s| s.id == id) {
                    self.send(
                        cx,
                        data::Command::ConnectSource(source.clone(), !source.selected),
                    );
                }
                return;
            }
            Dialog::Recipients => {
                if state
                    .choices
                    .iter()
                    .find(|s| s.id == id)
                    .is_some_and(|s| s.forum)
                {
                    self.send(cx, data::Command::ShareTopics(id));
                    self.open(cx, Dialog::Topics(id, title));
                } else {
                    self.send_target = Some((id, 0, title));
                    self.configure_dialog(cx);
                }
                return;
            }
            Dialog::Topics(chat, name) => {
                self.send_target = Some((chat, id, format!("{name} · {title}")));
                self.open(cx, Dialog::Recipients);
                self.send(
                    cx,
                    data::Command::ShareRecipients(
                        String::new(),
                        self.folder,
                        self.selected.as_ref().is_some_and(|t| t.document),
                    ),
                );
                return;
            }
            Dialog::Folders(recipients) => {
                self.folder = id as i32;
                self.send(
                    cx,
                    if recipients {
                        data::Command::ShareRecipients(
                            String::new(),
                            self.folder,
                            self.selected.as_ref().is_some_and(|t| t.document),
                        )
                    } else {
                        data::Command::Discover(String::new(), self.folder)
                    },
                );
                self.open(
                    cx,
                    if recipients {
                        Dialog::Recipients
                    } else {
                        Dialog::Chats
                    },
                );
                return;
            }
            Dialog::Playlists => self.send(cx, data::Command::PlaylistAdd(id, self.ids())),
            Dialog::Filter => {
                if id < 0 {
                    return;
                }
                let mut query = self.browsing_query();
                query.source = if id == 0 {
                    String::new()
                } else {
                    self.filter_sources
                        .get(id as usize - 1)
                        .map(|s| format!("{}:{}", if s.profile { "profile" } else { "chat" }, s.id))
                        .unwrap_or_default()
                };
                self.text(cx, ids!(source_filter), &title);
                self.send(cx, data::Command::Query(query, 0));
            }
            Dialog::Sort => {
                let mut query = self.browsing_query();
                query.sort = match id {
                    1 => data::Sort::Oldest,
                    2 => data::Sort::Title,
                    3 => data::Sort::Artist,
                    4 => data::Sort::Duration,
                    _ => data::Sort::Newest,
                };
                self.send(cx, data::Command::Query(query, 0));
            }
            Dialog::Order => self.send(
                cx,
                data::Command::Order(match id {
                    1 => data::Order::Reverse,
                    2 => data::Order::Shuffle,
                    3 => data::Order::Random,
                    _ => data::Order::Normal,
                }),
            ),
            Dialog::Repeat => self.send(
                cx,
                data::Command::Repeat(match id {
                    1 => data::Repeat::All,
                    2 => data::Repeat::One,
                    _ => data::Repeat::Off,
                }),
            ),
            Dialog::PlaylistActions(playlist) => match id {
                0 => {
                    self.navigate(cx, data::Screen::Playlist(playlist));
                    self.send(cx, data::Command::PlayAll);
                }
                1 => {
                    self.open(cx, Dialog::RenamePlaylist(playlist));
                    return;
                }
                2 => {
                    self.open(cx, Dialog::DeletePlaylist(playlist));
                    return;
                }
                3 => {
                    self.open_editor(cx, Some(playlist));
                    return;
                }
                _ => {}
            },
            _ => {}
        }
        self.close_dialog(cx);
    }
}

impl MatchEvent for App {
    fn handle_startup(&mut self, cx: &mut Cx) {
        self.client = Some(runtime::start());
        self.size = Vec2d { x: 1040., y: 760. };
        self.sync_ui(cx);
        self.layout(cx);
        #[cfg(not(target_os = "android"))]
        {
            let files = std::env::args_os()
                .skip(1)
                .map(std::path::PathBuf::from)
                .filter(|p| p.is_file())
                .collect::<Vec<_>>();
            if !files.is_empty() {
                self.send(cx, data::Command::Import(files));
            }
        }
    }
    fn handle_signal(&mut self, cx: &mut Cx) {
        self.sync_ui(cx);
        #[cfg(target_os = "android")]
        if crate::android::take_configuration_change() && self.settings_open {
            self.layout(cx);
            self.ui.redraw(cx);
        }
    }
    fn handle_shutdown(&mut self, cx: &mut Cx) {
        self.send(cx, data::Command::Quit);
    }
    fn handle_quit_requested(&mut self, cx: &mut Cx, _: &QuitRequestedEvent) -> bool {
        if self.closed {
            false
        } else {
            self.begin_quit(cx);
            true
        }
    }
    fn handle_background(&mut self, cx: &mut Cx) {
        self.hidden_at.get_or_insert_with(std::time::Instant::now);
        self.send(cx, data::Command::Visible(false));
    }
    fn handle_foreground(&mut self, cx: &mut Cx) {
        if let Some(mut catalog) = self
            .ui
            .widget(cx, ids!(catalog))
            .borrow_mut::<ui::Catalog>()
        {
            catalog.refresh_photos();
        }
        if let Some(mut choices) = self
            .ui
            .widget(cx, ids!(choices_body))
            .borrow_mut::<ui::Choices>()
        {
            choices.refresh_photos();
        }
        if self
            .hidden_at
            .take()
            .is_some_and(|t| t.elapsed().as_secs() >= 1800)
        {
            self.choose_sticker(cx);
        }
        self.send(cx, data::Command::Visible(true));
    }
    fn handle_next_frame(&mut self, cx: &mut Cx, _: &NextFrameEvent) {
        if let Some(command) = self.pending.pop_front() {
            self.send(cx, command);
        }
        if !self.pending.is_empty() {
            cx.new_next_frame();
        }
    }
    fn handle_timer(&mut self, cx: &mut Cx, event: &TimerEvent) {
        if event.timer_id == self.search_timer.0 {
            let mut query = self.browsing_query();
            query.search = self
                .ui
                .text_input(
                    cx,
                    if self.editor.is_some() {
                        ids!(editor_search)
                    } else {
                        ids!(search)
                    },
                )
                .text();
            self.send(cx, data::Command::Query(query, 0));
        }
        if event.timer_id == self.directory_timer.0
            && matches!(self.dialog, Dialog::Chats | Dialog::Recipients)
        {
            let text = self.ui.text_input(cx, ids!(panel_input)).text();
            self.send(
                cx,
                if self.dialog == Dialog::Recipients {
                    data::Command::ShareRecipients(
                        text,
                        self.folder,
                        self.selected.as_ref().is_some_and(|t| t.document),
                    )
                } else {
                    data::Command::Discover(text, self.folder)
                },
            );
        }
    }
    fn handle_back_pressed(&mut self, cx: &mut Cx) -> bool {
        if self.keyboard_visible {
            cx.set_key_focus(Area::Empty);
            cx.text_ime_was_dismissed();
            return true;
        }
        if self.dialog != Dialog::None {
            match self.dialog {
                Dialog::Sharing if self.sharing_return => self.open(cx, Dialog::Recipients),
                Dialog::Sharing => self.open(cx, Dialog::Settings),
                Dialog::Topics(..) => {
                    self.open(cx, Dialog::Recipients);
                    self.send(
                        cx,
                        data::Command::ShareRecipients(
                            String::new(),
                            self.folder,
                            self.selected.as_ref().is_some_and(|t| t.document),
                        ),
                    );
                }
                _ => self.close_dialog(cx),
            }
            true
        } else if self.snapshot().page.query.screen == data::Screen::OnDevice
            && self
                .settings_library_return
                .as_ref()
                .is_some_and(|saved| !saved.restoring)
        {
            let saved = self.settings_library_return.as_mut().unwrap();
            saved.restoring = true;
            let command = data::Command::Query(saved.query.clone(), saved.page);
            self.send(cx, command);
            self.open(cx, Dialog::Settings);
            true
        } else if self.editor.is_some() {
            self.leave_editor(cx);
            true
        } else if self.full_player {
            self.full_player = false;
            self.visible(cx, ids!(full_player), false);
            self.visible(cx, ids!(main), true);
            self.send(cx, data::Command::ObserveClock(false));
            true
        } else if self.selecting {
            self.selecting = false;
            self.selection.clear();
            self.pending_selection = None;
            self.all_selected_query = None;
            self.send(cx, data::Command::ClearSelection);
            self.selection_ui(cx);
            true
        } else if matches!(
            self.snapshot().page.query.screen,
            data::Screen::Profile(_)
                | data::Screen::Playlist(_)
                | data::Screen::Queue
                | data::Screen::OnDevice
        ) {
            let destination = match self.snapshot().page.query.screen {
                data::Screen::Profile(_) => data::Screen::Sources,
                data::Screen::Playlist(_) => data::Screen::Playlists,
                _ => data::Screen::Music,
            };
            self.navigate(cx, destination);
            true
        } else {
            false
        }
    }
    fn handle_key_down(&mut self, cx: &mut Cx, event: &KeyEvent) {
        if event.key_code == KeyCode::Escape {
            self.handle_back_pressed(cx);
        }
        if event.modifiers.logo || event.modifiers.control {
            if event.key_code == KeyCode::KeyO {
                self.send(cx, data::Command::PickFiles);
            }
            if event.key_code == KeyCode::KeyQ {
                self.begin_quit(cx);
            }
        } else if event.key_code == KeyCode::Space
            && self.dialog == Dialog::None
            && !cx.has_key_focus(self.ui.text_input(cx, ids!(search)).area())
        {
            self.send(cx, data::Command::Toggle);
        }
    }
    fn handle_actions(&mut self, cx: &mut Cx, actions: &Actions) {
        use data::Command as C;
        let state = self.snapshot();
        if self.ui.desktop_button(cx, ids!(app_close)).clicked(actions) {
            self.request_close(cx);
            return;
        }
        if self.click(cx, actions, ids!(editor_close)) {
            self.handle_back_pressed(cx);
            return;
        }
        if self
            .ui
            .text_input(cx, ids!(editor_name))
            .changed(actions)
            .is_some()
        {
            let name = self.ui.text_input(cx, ids!(editor_name)).text();
            if name.chars().count() > 80 {
                self.ui
                    .text_input(cx, ids!(editor_name))
                    .set_text(cx, &name.chars().take(80).collect::<String>());
            }
            self.editor_ui(cx);
        }
        if self
            .ui
            .text_input(cx, ids!(editor_search))
            .changed(actions)
            .is_some()
        {
            cx.stop_timer(self.search_timer);
            self.search_timer = cx.start_timeout(0.12);
        }
        if self.click(cx, actions, ids!(editor_select_all))
            && let Some(editor) = &mut self.editor
        {
            let query = state.page.query.clone();
            let add = editor.all_selected.as_ref() != Some(&query);
            editor.pending = Some((query.clone(), add));
            self.send(cx, C::SelectAll(query));
            self.editor_ui(cx);
        }
        if self.click(cx, actions, ids!(editor_save))
            && let Some(editor) = &mut self.editor
        {
            editor.saving = true;
            cx.set_key_focus(Area::Empty);
            cx.text_ime_was_dismissed();
            let command = C::PlaylistSave {
                playlist: editor.playlist,
                name: self.ui.text_input(cx, ids!(editor_name)).text(),
                ids: editor.ids.clone(),
            };
            self.send(cx, command);
            self.editor_ui(cx);
        }
        for (path, screen) in [
            (ids!(nav_music), data::Screen::Music),
            (ids!(rail_music), data::Screen::Music),
            (ids!(nav_favorites), data::Screen::Favorites),
            (ids!(rail_favorites), data::Screen::Favorites),
            (ids!(nav_playlists), data::Screen::Playlists),
            (ids!(rail_playlists), data::Screen::Playlists),
            (ids!(nav_sources), data::Screen::Sources),
            (ids!(rail_sources), data::Screen::Sources),
            (ids!(on_device), data::Screen::OnDevice),
        ] {
            if self.click(cx, actions, path) {
                self.navigate(cx, screen);
                return;
            }
        }
        if self.click(cx, actions, ids!(queue)) {
            let mut query = self.browsing_query();
            self.queue_return = Some(query.clone());
            self.queue_restore = None;
            query.screen = data::Screen::Queue;
            query.search.clear();
            query.source.clear();
            self.send(cx, C::Query(query, 0));
            self.open(cx, Dialog::Queue);
        }
        if self.click(cx, actions, ids!(settings)) {
            self.open(cx, Dialog::Settings);
        }
        if self.click(cx, actions, ids!(settings_done)) {
            self.close_dialog(cx);
        }
        for (section, tab, _) in settings::sections() {
            if self.click(cx, actions, tab) && self.settings_section != section {
                self.settings_section = section;
                settings::select(&self.ui, cx, section);
                self.refresh_settings_cache(cx);
            }
        }
        if self.click(cx, actions, ids!(panel_close)) {
            self.close_dialog(cx);
        }
        if self.click(cx, actions, ids!(auth_back)) {
            self.close_dialog(cx);
        }
        if self.click(cx, actions, ids!(panel_back)) {
            self.handle_back_pressed(cx);
        }
        if self.click(cx, actions, ids!(panel_cancel)) {
            self.close_dialog(cx);
        }
        if self.click(cx, actions, ids!(player_close)) {
            self.full_player = false;
            self.visible(cx, ids!(full_player), false);
            self.visible(cx, ids!(main), true);
            self.send(cx, C::ObserveClock(false));
        }
        if self.click(cx, actions, ids!(back)) {
            self.handle_back_pressed(cx);
        }
        if self.click(cx, actions, ids!(refresh))
            || self.click(cx, actions, ids!(source_refresh))
            || self.click(cx, actions, ids!(source_refresh_top))
        {
            if let data::Screen::Playlist(id) = state.page.query.screen {
                self.panel_anchor = self.anchor(cx, ids!(refresh));
                self.open(cx, Dialog::PlaylistActions(id));
            } else {
                self.send(cx, C::Refresh);
            }
        }
        if self.click(cx, actions, ids!(import)) || self.click(cx, actions, ids!(settings_import)) {
            self.send(cx, C::PickFiles);
        }
        if self.click(cx, actions, ids!(play_all)) {
            self.send(cx, C::PlayAll);
        }
        if self.click(cx, actions, ids!(mini_play)) || self.click(cx, actions, ids!(player_play)) {
            self.send(cx, C::Toggle);
        }
        if self.click(cx, actions, ids!(previous)) || self.click(cx, actions, ids!(player_previous))
        {
            self.send(cx, C::Previous);
        }
        if self.click(cx, actions, ids!(next)) || self.click(cx, actions, ids!(player_next)) {
            self.send(cx, C::Next);
        }
        if self.click(cx, actions, ids!(show_player)) {
            self.full_player = true;
            self.visible(cx, ids!(full_player), true);
            self.send(cx, C::ObserveClock(true));
            self.layout(cx);
        }
        if self.click(cx, actions, ids!(player_more)) {
            self.panel_anchor = self.anchor(cx, ids!(player_more));
            self.selected = state.playback.track.clone();
            if let Some(t) = &self.selected
                && !t.document
                && state.auth.kind == "authorizationStateReady"
            {
                self.send(cx, C::ProfileMember(t.id.clone()));
            }
            self.open(cx, Dialog::Track);
        }
        if self.click(cx, actions, ids!(source_filter)) {
            self.open(cx, Dialog::Filter);
        }
        if self.click(cx, actions, ids!(sort)) {
            self.open(cx, Dialog::Sort);
        }
        if self.click(cx, actions, ids!(order)) || self.click(cx, actions, ids!(library_order)) {
            self.open(cx, Dialog::Order);
        }
        if self.click(cx, actions, ids!(repeat)) {
            self.open(cx, Dialog::Repeat);
        }
        if self.click(cx, actions, ids!(speed)) {
            self.open(cx, Dialog::Speed);
        }
        if self.click(cx, actions, ids!(mute)) {
            self.send(cx, C::Mute);
        }
        if self.click(cx, actions, ids!(player_save)) {
            if let Some(t) = &state.playback.track {
                self.send(cx, C::Download(vec![t.id.clone()]));
            }
        }
        if self.click(cx, actions, ids!(player_favorite)) {
            if let Some(track) = &state.playback.track {
                self.send(cx, C::Favorite(vec![track.id.clone()], !track.liked));
            }
        }
        if self.click(cx, actions, ids!(player_share)) {
            self.send_target = None;
            self.selected = state.playback.track.clone();
            if let Some(track) = &self.selected {
                self.send(
                    cx,
                    C::ShareRecipients(String::new(), self.folder, track.document),
                );
            }
            self.open(cx, Dialog::Recipients);
        }
        for (path, value) in [(ids!(theme_light), "light"), (ids!(theme_dark), "dark")] {
            if self.click(cx, actions, path) {
                self.send(cx, C::Setting("theme".into(), json!(value)));
            }
        }
        for (path, value) in [
            (ids!(language_system), "system"),
            (ids!(language_en), "en"),
            (ids!(language_ru), "ru"),
        ] {
            if self.click(cx, actions, path) {
                self.send(cx, C::Setting("language".into(), json!(value)));
            }
        }
        if self.click(cx, actions, ids!(settings_storage)) {
            let list = self
                .ui
                .widget(cx, ids!(catalog))
                .portal_list(cx, ids!(list));
            let saved = SettingsLibraryReturn {
                query: self.browsing_query(),
                page: state.page.offset,
                first: list.first_id(),
                scroll: list.scroll_position(),
                full_player: self.full_player,
                restoring: false,
            };
            self.navigate(cx, data::Screen::OnDevice);
            self.settings_library_return = Some(saved);
        }
        if let Some(value) = self.ui.media_slider(cx, ids!(seek)).slided(actions) {
            self.text(
                cx,
                ids!(elapsed),
                &data::seconds(value * state.playback.duration),
            );
        }
        if let Some(value) = self.ui.media_slider(cx, ids!(seek)).end_slide(actions) {
            self.send(cx, C::Seek(value * state.playback.duration));
        }
        if let Some(value) = self.ui.media_slider(cx, ids!(volume)).slided(actions) {
            self.send(cx, C::Volume(value));
        }
        if let Some(value) = self.ui.media_slider(cx, ids!(speed_slider)).slided(actions) {
            self.send(cx, C::Speed(value));
        }
        for (id, speed) in [
            (ids!(speed_slow), 0.5),
            (ids!(speed_normal), 1.),
            (ids!(speed_fast), 1.5),
            (ids!(speed_double), 2.),
            (ids!(speed_triple), 3.),
        ] {
            if self.click(cx, actions, id) {
                self.send(cx, C::Speed(speed));
            }
        }
        if self.click(cx, actions, ids!(speed_minus)) {
            self.send(cx, C::SpeedStep(-1));
        }
        if self.click(cx, actions, ids!(speed_plus)) {
            self.send(cx, C::SpeedStep(1));
        }
        if self
            .ui
            .text_input(cx, ids!(search))
            .changed(actions)
            .is_some()
        {
            cx.stop_timer(self.search_timer);
            self.search_timer = cx.start_timeout(0.12);
        }
        if self
            .ui
            .text_input(cx, ids!(panel_input))
            .changed(actions)
            .is_some()
            && matches!(self.dialog, Dialog::Chats | Dialog::Recipients)
        {
            cx.stop_timer(self.directory_timer);
            self.directory_timer = cx.start_timeout(0.25);
        }
        if matches!(self.dialog, Dialog::Filter | Dialog::BookmarkThreshold)
            && self
                .ui
                .text_input(cx, ids!(panel_input))
                .changed(actions)
                .is_some()
        {
            self.configure_dialog(cx);
        }
        if self.click(cx, actions, ids!(add_source))
            || self.click(cx, actions, ids!(add_source_top))
        {
            if state.auth.kind == "authorizationStateReady" {
                self.open(cx, Dialog::AddSource);
            } else {
                self.send(cx, C::StartTelegram);
                self.open(cx, Dialog::Auth);
            }
        }
        if self.click(cx, actions, ids!(directory_folder)) {
            self.open(cx, Dialog::Folders(self.dialog == Dialog::Recipients));
        }
        if self.click(cx, actions, ids!(manage_sources)) {
            self.navigate(cx, data::Screen::Sources);
        }
        if self.click(cx, actions, ids!(create_playlist)) {
            self.open_editor(cx, None);
        }
        if self.click(cx, actions, ids!(profile_connect)) {
            if let data::Screen::Profile(id) = state.page.query.screen {
                if let Some(source) = state.sources.iter().find(|s| s.profile && s.id == id) {
                    self.send(cx, C::ConnectSource(source.clone(), true));
                }
            }
        }
        if self.click(cx, actions, ids!(profile_disconnect)) {
            if let data::Screen::Profile(id) = state.page.query.screen {
                self.open(cx, Dialog::RemoveSource(id, true));
            }
        }
        if self.click(cx, actions, ids!(select_mode)) {
            self.selecting = !self.selecting;
            self.selection.clear();
            self.pending_selection = None;
            self.all_selected_query = None;
            self.selection_ui(cx);
        }
        if self.click(cx, actions, ids!(selection_all)) {
            let query = state.page.query.clone();
            let add = self.all_selected_query.as_ref() != Some(&query);
            self.pending_selection = Some((query.clone(), add));
            self.send(cx, C::SelectAll(query));
            self.selection_ui(cx);
        }
        if self.click(cx, actions, ids!(selection_playlist)) {
            self.open(cx, Dialog::Playlists);
        }
        if self.click(cx, actions, ids!(selection_download)) {
            self.send(cx, C::Download(self.ids()));
        }
        if self.click(cx, actions, ids!(settings_account)) {
            if state.auth.kind == "authorizationStateReady" {
                self.open(cx, Dialog::Logout);
            } else {
                self.send(cx, C::StartTelegram);
                self.open(cx, Dialog::Auth);
            }
        }
        for (id, key) in [
            (ids!(settings_duplicates), "hide_duplicates"),
            (ids!(settings_bookmarks), "resume_long_audio"),
            (ids!(settings_wifi), "wifi_only"),
            (ids!(settings_discard), "discard_listened"),
            (ids!(settings_sender), "sharing_sender"),
            (ids!(settings_caption), "sharing_caption"),
            (ids!(settings_sharing_sender), "sharing_sender"),
            (ids!(settings_sharing_caption), "sharing_caption"),
        ] {
            if self.click(cx, actions, id) {
                self.send(cx, C::ToggleSetting(key.into()));
            }
        }
        if self.click(cx, actions, ids!(settings_minutes)) {
            self.open(cx, Dialog::BookmarkThreshold);
        }
        if self.click(cx, actions, ids!(cache_refresh)) {
            self.send(cx, C::CacheInfo);
        }
        if self.click(cx, actions, ids!(cache_clear)) {
            self.open(cx, Dialog::ClearCache);
        }
        if self.click(cx, actions, ids!(settings_cache_connect)) {
            self.settings_section = settings::Section::Telegram;
            settings::select(&self.ui, cx, self.settings_section);
        }
        if self.click(cx, actions, ids!(settings_sync_refresh)) {
            self.send(cx, C::Refresh);
        }
        if self.click(cx, actions, ids!(settings_transfers)) {
            self.send(cx, C::CancelDownloads);
        }
        if self.click(cx, actions, ids!(settings_update)) {
            self.send(cx, C::CheckUpdate);
        }
        if self.click(cx, actions, ids!(settings_quit)) {
            self.begin_quit(cx);
        }
        if self.click(cx, actions, ids!(auth_qr)) {
            self.send(cx, C::Qr);
        }
        if self.click(cx, actions, ids!(auth_phone)) {
            self.send(cx, C::ChangePhone);
        }
        if self.click(cx, actions, ids!(auth_resend)) {
            self.send(cx, C::Resend);
        }
        if self.click(cx, actions, ids!(auth_submit))
            || self.dialog == Dialog::Auth
                && self
                    .ui
                    .text_input(cx, ids!(panel_input))
                    .returned(actions)
                    .is_some()
        {
            let value = self.ui.text_input(cx, ids!(panel_input)).text();
            self.send(
                cx,
                C::AuthSubmit {
                    kind: state.auth.kind.clone(),
                    phone: state.auth.phone.clone(),
                    value,
                },
            );
            self.ui.text_input(cx, ids!(panel_input)).set_text(cx, "");
        }
        if self.click(cx, actions, ids!(panel_confirm))
            || self.dialog != Dialog::Auth
                && self
                    .ui
                    .text_input(cx, ids!(panel_input))
                    .returned(actions)
                    .is_some()
        {
            self.confirm(cx);
        }
        if let Some(track) = self.selected.clone() {
            if self.click(cx, actions, ids!(track_order)) {
                self.open(cx, Dialog::Order);
            }
            if self.click(cx, actions, ids!(track_favorite)) {
                self.send(cx, C::Favorite(vec![track.id.clone()], !track.liked));
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_playlist)) {
                self.open(cx, Dialog::Playlists);
            }
            if self.click(cx, actions, ids!(track_queue)) {
                self.send(cx, C::QueueAdd(vec![track.id.clone()]));
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_move_up))
                || self.click(cx, actions, ids!(track_move_down))
            {
                let from = track.queue_position.unwrap_or(self.selected_position);
                let to = if self.click(cx, actions, ids!(track_move_up)) {
                    from.saturating_sub(1)
                } else {
                    from + 1
                };
                if let data::Screen::Playlist(id) = state.page.query.screen {
                    self.send(cx, C::PlaylistMove(id, track.id.clone(), to < from));
                } else {
                    self.send(cx, C::QueueMove(from, to));
                }
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_remove_queue)) {
                self.send(
                    cx,
                    C::QueueRemove(track.queue_position.unwrap_or(self.selected_position)),
                );
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_download)) {
                self.send(cx, C::Download(vec![track.id.clone()]));
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_send)) {
                self.send_target = None;
                self.send(
                    cx,
                    C::ShareRecipients(String::new(), self.folder, track.document),
                );
                self.open(cx, Dialog::Recipients);
            }
            if self.click(cx, actions, ids!(track_profile_member)) {
                self.send(
                    cx,
                    C::SetProfileMember(track.id.clone(), state.profile_member != Some(true)),
                );
            }
            if self.click(cx, actions, ids!(track_profile)) {
                self.send(cx, C::OpenProfile(track.profile.to_string()));
                self.close_dialog(cx);
            }
            if self.click(cx, actions, ids!(track_original)) {
                let link = format!(
                    "https://t.me/c/{}/{}",
                    track
                        .chat
                        .unsigned_abs()
                        .to_string()
                        .trim_start_matches("100"),
                    track.message >> 20
                );
                if let Err(error) = crate::platform::open_url(&link) {
                    self.text(cx, ids!(notice), &error.to_string());
                }
            }
            if self.click(cx, actions, ids!(track_remove_playlist)) {
                if let data::Screen::Playlist(id) = state.page.query.screen {
                    self.send(cx, C::PlaylistRemove(id, vec![track.id.clone()]));
                    self.close_dialog(cx);
                }
            }
            if self.click(cx, actions, ids!(track_delete)) {
                self.open(cx, Dialog::Delete);
            }
        }
        let catalog = self.ui.widget(
            cx,
            if self.editor.is_some() {
                ids!(editor_catalog)
            } else if self.queue_return.is_some() {
                ids!(queue_catalog)
            } else {
                ids!(catalog)
            },
        );
        let list = catalog.portal_list(cx, ids!(list));
        let source_rows = if state.page.query.screen == data::Screen::Sources {
            ui::source_rows(&state)
        } else {
            Vec::new()
        };
        for (index, item) in list.items_with_actions(actions) {
            if item.button(cx, ids!(empty_connect)).clicked(actions) {
                self.send(cx, C::StartTelegram);
                self.open(cx, Dialog::Auth);
            }
            if item.button(cx, ids!(empty_import)).clicked(actions) {
                self.send(cx, C::PickFiles);
            }
            if item.button(cx, ids!(empty_add)).clicked(actions)
                && let data::Screen::Playlist(id) = state.page.query.screen
            {
                self.open_editor(cx, Some(id));
                return;
            }
            if item.button(cx, ids!(empty_profile)).clicked(actions)
                && let data::Screen::Profile(id) = state.page.query.screen
            {
                let link = state
                    .sources
                    .iter()
                    .find(|s| s.profile && s.id == id && !s.username.is_empty())
                    .map(|s| format!("https://t.me/{}", s.username.trim_start_matches('@')))
                    .unwrap_or_else(|| format!("tg://user?id={id}"));
                if let Err(error) = crate::platform::open_url(&link) {
                    self.text(cx, ids!(notice), &error.to_string());
                    self.visible(cx, ids!(notice), true);
                }
            }
            match state.page.query.screen {
                data::Screen::Queue => {
                    if let Some(track) = index
                        .checked_sub(state.page.offset)
                        .and_then(|i| state.page.tracks.get(i))
                    {
                        let position = track.queue_position.unwrap_or(index);
                        if item.button(cx, ids!(queue_play)).clicked(actions) {
                            self.send(cx, C::PlayQueue(position));
                        }
                        if item.button(cx, ids!(queue_up)).clicked(actions) {
                            self.send(cx, C::QueueMove(position, position.saturating_sub(1)));
                        }
                        if item.button(cx, ids!(queue_remove)).clicked(actions) {
                            self.send(cx, C::QueueRemove(position));
                        }
                    }
                }
                data::Screen::Sources => {
                    let source = match source_rows.get(index) {
                        Some(ui::SourceEntry::Source { index, .. }) => state.sources.get(*index),
                        _ => None,
                    };
                    if item.button(cx, ids!(open)).clicked(actions)
                        || item.button(cx, ids!(open_cover)).clicked(actions)
                    {
                        if index == 0 {
                            if state.auth.kind == "authorizationStateReady" {
                                self.send(cx, C::OpenProfile("me".into()));
                            } else {
                                self.send(cx, C::StartTelegram);
                                self.open(cx, Dialog::Auth);
                            }
                        } else if let Some(source) = source {
                            if source.profile {
                                self.send(cx, C::OpenProfile(source.id.to_string()));
                            } else {
                                let mut query = state.page.query.clone();
                                query.screen = data::Screen::Music;
                                query.source = format!("chat:{}", source.id);
                                self.send(cx, C::Query(query, 0));
                            }
                        }
                    }
                    if item.button(cx, ids!(select)).clicked(actions) {
                        if let Some(source) = source {
                            self.open(cx, Dialog::RemoveSource(source.id, source.profile));
                        } else if index == 0 {
                            if state.auth.kind == "authorizationStateReady" {
                                self.send(cx, C::OpenProfile("me".into()));
                            } else {
                                self.send(cx, C::StartTelegram);
                                self.open(cx, Dialog::Auth);
                            }
                        }
                    }
                }
                data::Screen::Playlists => {
                    if let Some(p) = ui::playlist_indices(&state)
                        .get(index)
                        .and_then(|index| state.playlists.get(*index))
                    {
                        if item.button(cx, ids!(open)).clicked(actions) {
                            self.navigate(cx, data::Screen::Playlist(p.id));
                        }
                        if item.button(cx, ids!(more)).clicked(actions) {
                            self.panel_anchor = Some(item.widget(cx, ids!(more)).area().rect(cx));
                            self.open(cx, Dialog::PlaylistActions(p.id));
                        }
                    }
                }
                _ => {
                    if let Some(track) = index
                        .checked_sub(state.page.offset)
                        .and_then(|i| state.page.tracks.get(i))
                    {
                        let held = [ids!(song), ids!(play_cover), ids!(row_hit)]
                            .iter()
                            .any(|path| item.button(cx, *path).long_pressed(actions));
                        if held && !self.selecting && self.editor.is_none() {
                            self.selecting = true;
                            self.selection.insert(track.id.clone());
                            self.selection_ui(cx);
                        }
                        if item.button(cx, ids!(song)).clicked(actions)
                            || item.button(cx, ids!(select_hit)).clicked(actions)
                            || item.button(cx, ids!(row_hit)).clicked(actions)
                            || item.button(cx, ids!(play_cover)).clicked(actions)
                        {
                            if let Some(editor) = &mut self.editor {
                                if editor.saving {
                                    continue;
                                }
                                editor.selection_revision += 1;
                                let canceled = editor.pending.take().is_some();
                                editor.all_selected = None;
                                if editor.ids.iter().any(|id| id == &track.id) {
                                    editor.ids.retain(|id| id != &track.id);
                                } else {
                                    editor.ids.push(track.id.clone());
                                }
                                if canceled {
                                    self.send(cx, C::ClearSelection);
                                }
                                self.editor_ui(cx);
                            } else if self.selecting {
                                if self.pending_selection.take().is_some() {
                                    self.send(cx, C::ClearSelection);
                                }
                                self.all_selected_query = None;
                                if !self.selection.insert(track.id.clone()) {
                                    self.selection.remove(&track.id);
                                }
                                self.selection_ui(cx);
                            } else if let Some(position) = track.queue_position {
                                self.send(cx, C::PlayQueue(position));
                            } else {
                                self.send(cx, C::Play(track.id.clone()));
                            }
                        }
                        if item.button(cx, ids!(favorite)).clicked(actions) {
                            self.send(cx, C::Favorite(vec![track.id.clone()], !track.liked));
                        }
                        if item.button(cx, ids!(more)).clicked(actions) {
                            self.panel_anchor = Some(item.widget(cx, ids!(more)).area().rect(cx));
                            self.selected_position = index;
                            self.selected = Some(track.clone());
                            if !track.document && state.auth.kind == "authorizationStateReady" {
                                self.send(cx, C::ProfileMember(track.id.clone()));
                            }
                            self.open(cx, Dialog::Track);
                        }
                    }
                }
            }
        }
        let list = self
            .ui
            .widget(cx, ids!(choices_body))
            .portal_list(cx, ids!(list));
        for (index, item) in list.items_with_actions(actions) {
            if item.button(cx, ids!(choice)).clicked(actions) {
                self.choose(cx, index);
            }
        }
        let quick = self
            .ui
            .widget(cx, ids!(quick_choices))
            .portal_list(cx, ids!(list));
        for (index, item) in quick.items_with_actions(actions) {
            if item.button(cx, ids!(choice)).clicked(actions)
                && let Some(source) = self.quick_recipients.get(index).cloned()
            {
                self.select_recipient(cx, &source);
            }
        }
        let folders = self
            .ui
            .widget(cx, ids!(folder_choices))
            .portal_list(cx, ids!(list));
        for (index, item) in folders.items_with_actions(actions) {
            if item.button(cx, ids!(choice)).clicked(actions) {
                self.folder = match index {
                    0 => 0,
                    1 => -1,
                    _ => state.folders.get(index - 2).map(|f| f.id).unwrap_or(0),
                };
                let search = self.ui.text_input(cx, ids!(panel_input)).text();
                self.send(
                    cx,
                    C::ShareRecipients(
                        search,
                        self.folder,
                        self.selected.as_ref().is_some_and(|t| t.document),
                    ),
                );
                self.configure_dialog(cx);
            }
        }
        if self.click(cx, actions, ids!(send_settings)) {
            self.sharing_return = true;
            self.open(cx, Dialog::Sharing);
        }
        if self.click(cx, actions, ids!(send_pin))
            && let Some((id, _, _)) = &self.send_target
        {
            let key = format!("sharing_pins_{}", state.me);
            let mut pins: Vec<data::Source> =
                serde_json::from_value(state.settings[&key].clone()).unwrap_or_default();
            if pins.iter().any(|source| source.id == *id) {
                pins.retain(|source| source.id != *id);
            } else if let Some(source) = state
                .choices
                .iter()
                .chain(self.quick_recipients.iter())
                .find(|source| source.id == *id)
            {
                pins.insert(0, source.clone());
                pins.truncate(8);
            }
            self.send(cx, C::Setting(key, json!(pins)));
        }
    }
}

impl AppMain for App {
    fn script_mod(vm: &mut ScriptVm) -> ScriptValue {
        makepad_widgets::script_mod(vm);
        script_eval!(vm,{
            use mod.prelude.widgets.*
            mod.theme.font_regular = TextStyle{font_family:FontFamily{latin := FontMember{res:crate_resource("makepad_widgets:resources/RobotoFlex.ttf") weight:400.0 asc:0.0 desc:0.0}} line_spacing:1.0};
            mod.theme.font_bold = TextStyle{font_family:FontFamily{latin := FontMember{res:crate_resource("makepad_widgets:resources/RobotoFlex.ttf") weight:600.0 asc:0.0 desc:0.0}} line_spacing:1.0};
        });
        if base_theme(vm.cx_mut()) == BaseTheme::Light {
            script_eval!(vm,{mod.theme.spotygram_dark = 0.0; mod.theme.color_bg_app = #xf9fafc; mod.theme.color_text = #x1d3032; mod.theme.color_text_meta = #x596575; mod.theme.color_primary = #x137b8a;});
        } else {
            script_eval!(vm,{mod.theme.spotygram_dark = 1.0; mod.theme.color_bg_app = #x080a0e; mod.theme.color_text = #xf1f6f3; mod.theme.color_text_meta = #xb0b8c3; mod.theme.color_primary = #x58c9d2;});
        }
        crate::design::script_mod(vm);
        crate::ui::script_mod(vm);
        self::script_mod(vm)
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event) {
        if self.drag_sheet(cx, event) {
            return;
        }
        let drop_area = self.ui.widget(cx, ids!(root)).area();
        match event.drag_hits(cx, drop_area) {
            DragHit::Drag(drag) => {
                if drag
                    .items
                    .iter()
                    .any(|item| matches!(item, DragItem::FilePath { .. }))
                {
                    *drag.response.lock().unwrap() = DragResponse::Copy;
                }
            }
            DragHit::Drop(drop) => {
                let files = drop
                    .items
                    .iter()
                    .filter_map(|item| match item {
                        DragItem::FilePath { path, .. } => Some(std::path::PathBuf::from(path)),
                        _ => None,
                    })
                    .collect::<Vec<_>>();
                if !files.is_empty() {
                    self.send(cx, data::Command::Import(files));
                }
            }
            _ => {}
        }
        if let Event::WindowGeomChange(change) = event {
            self.size = change.new_geom.inner_size;
            let safe = change.new_geom.safe_area_insets;
            let top = safe.top;
            let bottom = safe.bottom;
            let padding = Inset {
                top,
                bottom,
                left: safe.left,
                right: safe.right,
            };
            self.safe = padding;
            self.layout(cx);
        }
        if let Event::VirtualKeyboard(keyboard) = event {
            match keyboard {
                VirtualKeyboardEvent::WillShow { height, .. }
                | VirtualKeyboardEvent::DidShow { height, .. } => {
                    self.keyboard_height = *height;
                    self.keyboard_visible = true;
                }
                VirtualKeyboardEvent::WillHide { .. } | VirtualKeyboardEvent::DidHide { .. } => {
                    // Dismissing the mobile keyboard ends field editing. Some
                    // Android input devices are reported as physical keyboards;
                    // retaining focus then makes TextInput request the IME again.
                    #[cfg(target_os = "android")]
                    if self.keyboard_visible {
                        cx.set_key_focus(Area::Empty);
                        cx.text_ime_was_dismissed();
                    }
                    self.keyboard_height = 0.;
                    self.keyboard_visible = false;
                }
            }
            if self.dialog != Dialog::None {
                self.configure_dialog(cx);
            } else {
                self.layout(cx);
            }
            self.ui.redraw(cx);
        }
        if let Event::WindowCloseRequested(request) = event {
            if !self.closed {
                request.accept_close.set(false);
                self.request_close(cx);
            }
        }
        let pointer = match event {
            Event::MouseDown(finger) => Some(finger.abs),
            Event::TouchUpdate(touch) => touch
                .touches
                .iter()
                .find(|t| t.state == TouchState::Start)
                .map(|t| t.abs),
            _ => None,
        };
        if let Some(pointer) = pointer {
            if self.dialog == Dialog::Settings {
                cx.set_key_focus(Area::Empty);
            } else if self.dialog != Dialog::None {
                let area = self.ui.widget(cx, ids!(panel)).area();
                if area.is_valid(cx) && !area.rect(cx).contains(pointer) {
                    self.close_dialog(cx);
                    return;
                }
            }
        }
        if self.settings_open || self.dialog == Dialog::Speed {
            let path = if self.dialog == Dialog::Settings {
                ids!(settings_page).as_slice()
            } else {
                ids!(panel).as_slice()
            };
            if let Some(root) = self.ui.widget(cx, path).area().draw_list_id() {
                self.settings_nav_control.handle_event(cx, event, root);
            }
        }
        self.match_event(cx, event);
        if matches!(event, Event::Draw(_)) {
            // Style reloads also reach controls that were hidden. Restore their
            // desired glyph before drawing; unchanged SVGs keep their GPU mesh.
            for path in [
                ids!(back),
                ids!(panel_close),
                ids!(track_favorite),
                ids!(track_profile_member),
                ids!(refresh),
                ids!(player_favorite),
                ids!(mini_play),
                ids!(player_play),
                ids!(mute),
                ids!(order),
                ids!(library_order),
                ids!(repeat),
            ] {
                ui::refresh_icon(&self.ui, cx, path);
            }
        }
        if matches!(event, Event::LiveEdit | Event::ScriptReapply) {
            if let Some(mut catalog) = self
                .ui
                .widget(cx, ids!(catalog))
                .borrow_mut::<ui::Catalog>()
            {
                catalog.refresh_style();
            }
            self.localize(cx);
            self.state = None;
            self.sync_ui(cx);
            if let Some(choice) = self.sticker {
                let _ = self
                    .ui
                    .image(cx, ids!(header_sticker))
                    .load_png_from_data(cx, crate::design::STICKERS[choice]);
            }
            self.configure_dialog(cx);
            self.layout(cx);
            self.selection_ui(cx);
        }
        if !matches!(self.dialog, Dialog::None | Dialog::Settings)
            && matches!(
                event,
                Event::MouseDown(_)
                    | Event::MouseUp(_)
                    | Event::MouseMove(_)
                    | Event::Scroll(_)
                    | Event::Pinch(_)
                    | Event::TouchUpdate(_)
            )
        {
            self.ui
                .widget(cx, ids!(overlay))
                .handle_event(cx, event, &mut Scope::empty());
        } else {
            self.ui.handle_event(cx, event, &mut Scope::empty());
        }
    }
}
