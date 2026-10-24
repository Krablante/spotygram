use crate::ui::MediaSliderWidgetRefExt;
use crate::{model as data, runtime, ui};
use makepad_widgets::makepad_platform::event::finger::TouchState;
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
        ui: Root {
            main_window := Window {
                window.title: "spotygram rust"
                window.app_id: "app.spotygram.rust"
                window.inner_size: vec2(1040,760)
                pass.clear_color: theme.color_bg_app
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
    NewPlaylist,
    RenamePlaylist(i64),
    PlaylistActions(i64),
    Recipients,
    Folders(bool),
    Topics(i64, String),
    ConfirmSend(i64, i64, String),
    Delete,
    DeletePlaylist(i64),
    ClearCache,
    Logout,
    Speed,
    Order,
    Repeat,
    BookmarkThreshold,
}

#[derive(Script, ScriptHook)]
pub struct App {
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
}

impl App {
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
        self.ui.glass_button(cx, path).clicked(actions) || self.ui.button(cx, path).clicked(actions)
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
        ]
        .iter()
        .any(|p| p.as_slice() == path)
            || path == ids!(heading)
                && matches!(
                    state.page.query.screen,
                    data::Screen::Playlist(_) | data::Screen::Profile(_)
                )
            || path == ids!(panel_title) && matches!(self.dialog, Dialog::PlaylistActions(_));
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
        self.selection.clear();
        self.selecting = false;
        self.full_player = false;
        self.selection_ui(cx);
        self.close_dialog(cx);
        self.send(cx, data::Command::ObserveClock(false));
        let mut query = self.snapshot().page.query.clone();
        query.screen = screen;
        query.search.clear();
        query.source.clear();
        self.ui.text_input(cx, ids!(search)).set_text(cx, "");
        self.send(cx, data::Command::Query(query, 0));
    }

    fn close_dialog(&mut self, cx: &mut Cx) {
        self.dialog = Dialog::None;
        self.visible(cx, ids!(overlay), false);
        self.ui.text_input(cx, ids!(panel_input)).set_text(cx, "");
        self.ui.image(cx, ids!(auth_qrcode)).set_texture(cx, None);
        self.qr_url.clear();
    }

    fn selection_ui(&self, cx: &mut Cx) {
        let catalog = self.ui.widget(cx, ids!(catalog));
        if let Some(mut catalog) = catalog.borrow_mut::<ui::Catalog>() {
            catalog.selection = self.selection.clone();
            catalog.redraw(cx);
        }
        self.text(
            cx,
            ids!(selection_count),
            &format!("{} selected", self.selection.len()),
        );
    }

    fn open(&mut self, cx: &mut Cx, dialog: Dialog) {
        self.dialog = dialog;
        self.ui.text_input(cx, ids!(panel_input)).set_text(cx, "");
        self.ui
            .view(cx, ids!(panel_scroll))
            .set_scroll_pos(cx, Vec2d::default());
        self.ui
            .widget(cx, ids!(choices_body))
            .portal_list(cx, ids!(list))
            .set_first_id_and_scroll(0, 0.);
        self.configure_dialog(cx);
    }

    fn configure_dialog(&mut self, cx: &mut Cx) {
        let state = self.snapshot();
        let dialog = self.dialog.clone();
        self.visible(cx, ids!(overlay), dialog != Dialog::None);
        for path in [
            ids!(settings_body),
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
        ] {
            self.visible(cx, path, false);
        }
        self.ui
            .text_input(cx, ids!(panel_input))
            .set_is_password(cx, false);
        self.choices.clear();
        let title = match &dialog {
            Dialog::None => "",
            Dialog::Settings => "Settings",
            Dialog::Auth => "Connect Telegram",
            Dialog::Track => "Recording",
            Dialog::Chats => "Choose chats",
            Dialog::Profile => "Open a profile",
            Dialog::Filter => "Music sources",
            Dialog::Sort => "Sort music",
            Dialog::Playlists => "Add to playlist",
            Dialog::NewPlaylist => "New playlist",
            Dialog::RenamePlaylist(_) => "Rename playlist",
            Dialog::PlaylistActions(_) => "Playlist",
            Dialog::Recipients => "Send audio",
            Dialog::Folders(_) => "Chat folders",
            Dialog::Topics(..) => "Choose forum topic",
            Dialog::ConfirmSend(..) => "Confirm send",
            Dialog::Delete => "Remove copy",
            Dialog::ClearCache => "Clear music cache",
            Dialog::Logout => "Sign out",
            Dialog::DeletePlaylist(_) => "Delete playlist",
            Dialog::Speed => "Playback speed",
            Dialog::Order => "Playback order",
            Dialog::Repeat => "Repeat",
            Dialog::BookmarkThreshold => "Bookmark threshold",
        };
        self.text(cx, ids!(panel_title), title);
        match &dialog {
            Dialog::Settings => {
                self.visible(cx, ids!(settings_body), true);
                self.text(
                    cx,
                    ids!(settings_account),
                    if state.auth.kind == "authorizationStateReady" {
                        "Sign out of Telegram"
                    } else {
                        "Connect Telegram"
                    },
                );
                self.text(
                    cx,
                    ids!(settings_theme),
                    &format!(
                        "Appearance: {}",
                        state.settings["theme"].as_str().unwrap_or("system")
                    ),
                );
                self.text(
                    cx,
                    ids!(settings_language),
                    &format!(
                        "Language: {}",
                        match state.settings["language"].as_str().unwrap_or("system") {
                            "ru" => "Русский",
                            "en" => "English",
                            _ =>
                                if state.language == "ru" {
                                    "Системный"
                                } else {
                                    "System"
                                },
                        }
                    ),
                );
                for (id, key, label) in [
                    (
                        ids!(settings_duplicates),
                        "hide_duplicates",
                        "Hide duplicates",
                    ),
                    (
                        ids!(settings_bookmarks),
                        "resume_long_audio",
                        "Resume long recordings",
                    ),
                    (
                        ids!(settings_wifi),
                        "wifi_only",
                        "Downloads over Wi-Fi only",
                    ),
                    (
                        ids!(settings_discard),
                        "discard_listened",
                        "Do not keep played tracks",
                    ),
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
                    self.text(
                        cx,
                        id,
                        &format!(
                            "{label}: {}",
                            if state.settings[key] == true {
                                "On"
                            } else {
                                "Off"
                            }
                        ),
                    );
                }
                self.text(
                    cx,
                    ids!(settings_minutes),
                    &format!(
                        "Bookmark threshold: {} minutes",
                        state.settings["resume_minutes"].as_u64().unwrap_or(30)
                    ),
                );
                self.text(
                    cx,
                    ids!(settings_storage),
                    &format!(
                        "On device: {} · {:.1} MiB",
                        crate::i18n::tracks(&state.language, state.storage_count),
                        state.storage_bytes as f64 / 1048576.
                    ),
                );
                self.text(
                    cx,
                    ids!(settings_transfers),
                    &format!(
                        "{} · {} · {:.0}%",
                        crate::i18n::tr(&state.language, "Cancel downloads"),
                        state.downloads,
                        state.transfer_progress * 100.
                    ),
                );
                self.text(cx, ids!(settings_sync), &state.sync);
            }
            Dialog::Auth => {
                self.visible(cx, ids!(auth_actions), true);
                self.visible(cx, ids!(auth_mark), true);
                let kind = state.auth.kind.as_str();
                let (message,placeholder)=match kind {
                    "authorizationStateWaitCode"=>(format!("Enter the code sent to {}. {}",state.auth.phone,state.auth.detail),"Login code"),
                    "authorizationStateWaitPassword"=>(format!("Enter your Telegram password. {}",state.auth.detail),"Password"),
                    "authorizationStateWaitEmailAddress"=>("Telegram needs an email address to continue.".into(),"Email address"),
                    "authorizationStateWaitEmailCode"=>(format!("Enter the code sent to {}.",state.auth.detail),"Email code"),
                    "authorizationStateWaitOtherDeviceConfirmation"=>(format!("Open this link on a signed-in device:\n{}",state.auth.detail),""),
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
                    self.visible(cx, ids!(auth_mark), false);
                    self.visible(cx, ids!(auth_qr_area), true);
                    if self.qr_url != state.auth.detail {
                        if let Ok(code) = qrcode::QrCode::new(state.auth.detail.as_bytes()) {
                            let svg = code
                                .render::<qrcode::render::svg::Color>()
                                .min_dimensions(440, 440)
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
                    self.text(cx, ids!(track_title), &track.title);
                    self.text(cx, ids!(track_artist), track.subtitle());
                    ui::set_image(&self.ui, cx, ids!(track_cover.art), &track.art);
                    self.text(
                        cx,
                        ids!(track_favorite),
                        if track.liked {
                            "Remove from favorites"
                        } else {
                            "Add to favorites"
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
                        if state.profile_member == Some(true) {
                            "Remove from profile"
                        } else {
                            "To profile"
                        },
                    );
                    let connected = state.auth.kind == "authorizationStateReady";
                    self.visible(cx, ids!(track_profile_member), !track.document && connected);
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
                self.visible(cx, ids!(directory_folder), true);
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
                self.choices.push((0, "All sources".into()));
                self.choices
                    .extend(state.sources.iter().filter(|s| s.selected).map(|s| {
                        (
                            s.id,
                            format!(
                                "{}{}",
                                if s.profile { "Profile · " } else { "Chat · " },
                                s.title
                            ),
                        )
                    }));
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
            Dialog::NewPlaylist | Dialog::RenamePlaylist(_) => {
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
                ];
                if let Some(p) = state.playlists.iter().find(|p| p.id == *id) {
                    self.text(cx, ids!(panel_title), &p.name);
                }
            }
            Dialog::ConfirmSend(_, _, recipient) => {
                self.visible(cx, ids!(panel_message), true);
                let title = self
                    .selected
                    .as_ref()
                    .map(|t| t.title.as_str())
                    .unwrap_or("Selected recording");
                self.text(
                    cx,
                    ids!(panel_message),
                    &format!(
                        "Send “{title}” to {recipient}?\nOriginal sender: {}\nOriginal caption: {}",
                        if state.settings["sharing_sender"] == true {
                            "kept"
                        } else {
                            "omitted"
                        },
                        if state.settings["sharing_caption"] == true {
                            "kept"
                        } else {
                            "omitted"
                        }
                    ),
                );
                self.confirm_button(cx, "Send recording");
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
                    &format!("{:.2}×", state.playback.speed),
                );
                self.ui
                    .media_slider(cx, ids!(speed_slider))
                    .set_value(cx, state.playback.speed);
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
                self.ui
                    .text_input(cx, ids!(panel_input))
                    .set_empty_text(cx, "Minutes".into());
                self.confirm_button(cx, "Save threshold");
            }
            Dialog::None => {}
        }
        let choices = self.ui.widget(cx, ids!(choices_body));
        if let Some(mut widget) = choices.borrow_mut::<ui::Choices>() {
            widget.labels = self
                .choices
                .iter()
                .map(|(_, s)| {
                    if matches!(
                        dialog,
                        Dialog::Sort | Dialog::Order | Dialog::Repeat | Dialog::PlaylistActions(_)
                    ) {
                        crate::i18n::tr(&state.language, s).into_owned()
                    } else {
                        s.clone()
                    }
                })
                .collect();
            widget.redraw(cx);
        }
        let mut choices = self.ui.widget(cx, ids!(choices_body));
        let choices_height = (self.choices.len().max(1) as f64 * 58.).min(380.);
        script_apply_eval!(cx,choices,{height:#(choices_height)});
        let input = self.ui.text_input(cx, ids!(panel_input));
        // Password and typed input survive language changes; only the placeholder changes.
        if let Some(mut input) = input.borrow_mut() {
            let placeholder = input.empty_text().to_owned();
            input.set_empty_text(
                cx,
                crate::i18n::tr(&state.language, &placeholder).into_owned(),
            );
        }
        self.visible(cx, ids!(panel_error), !state.error.is_empty());
        self.text(cx, ids!(panel_error), &state.error);
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
        self.visible(cx, ids!(panel_confirm), true);
        self.text(cx, ids!(panel_confirm), text);
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
        if old
            .as_ref()
            .is_none_or(|old| old.language != state.language)
        {
            self.localize(cx);
        }
        let page_changed = old.as_ref().is_none_or(|old| {
            old.page.revision != state.page.revision
                || old.page.offset != state.page.offset
                || old.page.query != state.page.query
        });
        if old
            .as_ref()
            .is_some_and(|old| old.page.query != state.page.query)
        {
            self.ui
                .widget(cx, ids!(catalog))
                .portal_list(cx, ids!(list))
                .set_first_id_and_scroll(0, 0.);
        }
        let lists_changed = old.as_ref().is_none_or(|old| {
            !Arc::ptr_eq(&old.sources, &state.sources)
                || !Arc::ptr_eq(&old.playlists, &state.playlists)
        });
        let screen = &state.page.query.screen;
        let title = match screen {
            data::Screen::Music => "Music".to_owned(),
            data::Screen::Favorites => "Favorites".into(),
            data::Screen::Playlists => "Playlists".into(),
            data::Screen::OnDevice => "On device".into(),
            data::Screen::Sources => "Sources".into(),
            data::Screen::Queue => "Queue".into(),
            data::Screen::Playlist(id) => state
                .playlists
                .iter()
                .find(|p| p.id == *id)
                .map(|p| p.name.clone())
                .unwrap_or("Playlist".into()),
            data::Screen::Profile(id) => state
                .sources
                .iter()
                .find(|s| s.profile && s.id == *id)
                .map(|s| s.title.clone())
                .unwrap_or("Profile music".into()),
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
            !matches!(screen, data::Screen::Sources | data::Screen::Playlists),
        );
        self.visible(
            cx,
            ids!(back),
            matches!(
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
            matches!(
                screen,
                data::Screen::Music
                    | data::Screen::Favorites
                    | data::Screen::OnDevice
                    | data::Screen::Playlist(_)
            ),
        );
        self.visible(
            cx,
            ids!(collection_controls),
            matches!(screen, data::Screen::Playlists | data::Screen::Sources),
        );
        self.visible(
            cx,
            ids!(create_playlist),
            *screen == data::Screen::Playlists,
        );
        self.visible(cx, ids!(add_chats), *screen == data::Screen::Sources);
        self.visible(cx, ids!(add_profile), *screen == data::Screen::Sources);
        self.visible(
            cx,
            ids!(profile_controls),
            matches!(screen, data::Screen::Profile(_)),
        );
        self.visible(cx, ids!(selection_controls), self.selecting);
        self.text(
            cx,
            ids!(selection_count),
            &format!("{} selected", self.selection.len()),
        );
        self.text(
            cx,
            ids!(header_status),
            if state.busy {
                &state.status
            } else {
                "Rust preview"
            },
        );
        let notice = if !state.error.is_empty() {
            &state.error
        } else {
            &state.status
        };
        self.visible(cx, ids!(notice), !notice.is_empty());
        self.text(cx, ids!(notice), notice);
        let playback = &state.playback;
        let active = playback.track.is_some();
        self.visible(cx, ids!(player_dock), active);
        self.visible(cx, ids!(full_player), self.full_player && active);
        self.visible(cx, ids!(main), !self.full_player || !active);
        for path in [ids!(mini_play), ids!(previous), ids!(next)] {
            self.visible(cx, path, active);
        }
        self.visible(cx, ids!(previous), active && self.size.x >= 480.);
        if let Some(track) = &playback.track {
            for id in [ids!(mini_title), ids!(player_title)] {
                self.text(cx, id, &track.title);
            }
            for id in [ids!(mini_artist), ids!(player_artist)] {
                self.text(cx, id, track.subtitle());
            }
            for id in [ids!(mini_play), ids!(player_play)] {
                self.text(
                    cx,
                    id,
                    if playback.buffering {
                        "…"
                    } else if playback.playing {
                        "Ⅱ"
                    } else {
                        "▶"
                    },
                );
            }
            self.text(cx, ids!(elapsed), &data::seconds(playback.position));
            self.text(cx, ids!(duration), &data::seconds(playback.duration));
            self.text(cx, ids!(speed), &format!("{}×", playback.speed));
            self.text(cx, ids!(volume_label), &format!("{:.0}%", playback.volume));
            self.text(cx, ids!(mute), if playback.muted { "×♪" } else { "♪" });
            self.text(
                cx,
                ids!(order),
                match playback.order {
                    data::Order::Normal => "↕",
                    data::Order::Reverse => "↓↑",
                    data::Order::Shuffle => "⤨",
                    data::Order::Random => "?",
                },
            );
            self.text(
                cx,
                ids!(repeat),
                match playback.repeat {
                    data::Repeat::Off => "↻",
                    data::Repeat::All => "↻∞",
                    data::Repeat::One => "↻1",
                },
            );
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
                Dialog::Settings => {
                    s.settings != state.settings
                        || s.storage_bytes != state.storage_bytes
                        || s.downloads != state.downloads
                        || s.transfer_progress != state.transfer_progress
                        || s.sync != state.sync
                }
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
                }
                _ => false,
            });
            if reconfigure {
                self.configure_dialog(cx);
                if self.dialog == Dialog::Auth
                    && old
                        .as_ref()
                        .is_some_and(|old| old.auth.kind != state.auth.kind)
                {
                    self.ui
                        .view(cx, ids!(panel_scroll))
                        .set_scroll_pos(cx, Vec2d::default());
                }
            }
            if self.dialog == Dialog::Auth && state.auth.kind == "authorizationStateReady" {
                self.close_dialog(cx);
            }
        }
    }

    fn localize(&self, cx: &mut Cx) {
        for (path, text) in [
            (ids!(preview_label), "RUST PREVIEW"),
            (ids!(playing_label), "NOW PLAYING"),
            (ids!(rail_music), "Music"),
            (ids!(nav_music), "Music"),
            (ids!(rail_favorites), "Favorites"),
            (ids!(nav_favorites), "Favorites"),
            (ids!(rail_playlists), "Playlists"),
            (ids!(nav_playlists), "Playlists"),
            (ids!(rail_sources), "Sources"),
            (ids!(nav_sources), "Sources"),
            (ids!(rail_settings), "Settings"),
            (ids!(source_filter), "All sources"),
            (ids!(play_all), "▶   Play all"),
            (ids!(create_playlist), "New playlist"),
            (ids!(add_chats), "Add chats"),
            (ids!(add_profile), "Add profile"),
            (ids!(profile_connect), "Add to library"),
            (ids!(profile_play), "▶   Play"),
            (ids!(queue), "≡  Queue"),
            (ids!(player_save), "↓  Save"),
            (ids!(auth_submit), "Continue"),
            (ids!(auth_qr), "Sign in with QR code"),
            (ids!(auth_phone), "Use another phone number"),
            (ids!(auth_resend), "Resend code"),
            (ids!(settings_import), "Import audio files"),
            (ids!(settings_clear), "Clear music cache"),
            (ids!(settings_transfers), "Cancel downloads"),
            (ids!(settings_update), "Check Rust preview updates"),
            (ids!(settings_quit), "Quit spotygram rust"),
            (
                ids!(settings_about),
                "spotygram rust · 0.1.0\nA personal testing build. Local data is separate from the stable app.",
            ),
            (ids!(track_play), "Play"),
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
        let width = self.size.x.max(280.);
        let height = self.size.y.max(360.);
        let wide = width >= 840.;
        self.visible(cx, ids!(rail), wide);
        self.visible(cx, ids!(bottom_nav), !wide);
        self.visible(cx, ids!(brand), !wide);
        self.visible(cx, ids!(volume_row), cfg!(not(target_os = "android")));
        self.visible(
            cx,
            ids!(previous),
            self.size.x >= 480. && self.snapshot().playback.track.is_some(),
        );
        let mut panel = self.ui.widget(cx, ids!(panel));
        let panel_width = (width - 28.).clamp(250., 520.);
        script_apply_eval!(cx,panel,{width:#(panel_width)});
        let mut panel_scroll = self.ui.widget(cx, ids!(panel_scroll));
        let available = (height - 170.).max(130.);
        let available = match self.dialog {
            Dialog::Settings | Dialog::Track => available.min(580.),
            Dialog::Chats | Dialog::Recipients | Dialog::Topics(..) => available.min(380.),
            Dialog::Auth => available.min(560.),
            Dialog::Speed => available.min(150.),
            _ => available.min(260.),
        };
        // Keep the panel header and confirmation outside the scrolling viewport.
        let fit = Size::fit();
        script_apply_eval!(cx,panel_scroll,{height:#(fit) max_height:#(available)});
        let mut columns = self.ui.widget(cx, ids!(player_columns));
        let flow = if wide { Flow::right() } else { Flow::Down };
        script_apply_eval!(cx,columns,{flow:#(flow)});
        let mut art = self.ui.widget(cx, ids!(player_art));
        let size = if wide {
            (width * 0.32).clamp(220., 380.)
        } else {
            (height * 0.29).clamp(120., 260.).min(width - 64.)
        };
        script_apply_eval!(cx,art,{width:#(size) height:#(size)});
    }

    fn begin_quit(&mut self, cx: &mut Cx) {
        if !self.quitting {
            self.quitting = true;
            self.send(cx, data::Command::Quit);
            self.text(cx, ids!(notice), "Saving and closing…");
            self.visible(cx, ids!(notice), true);
        }
    }

    fn confirm(&mut self, cx: &mut Cx) {
        let state = self.snapshot();
        let input = self.ui.text_input(cx, ids!(panel_input)).text();
        match self.dialog.clone() {
            Dialog::Profile => self.send(cx, data::Command::OpenProfile(input)),
            Dialog::NewPlaylist => self.send(cx, data::Command::PlaylistCreate(input)),
            Dialog::RenamePlaylist(id) => self.send(cx, data::Command::PlaylistRename(id, input)),
            Dialog::ConfirmSend(recipient, topic, _) => {
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
            Dialog::Delete => self.send(cx, data::Command::Delete(self.ids())),
            Dialog::DeletePlaylist(id) => self.send(cx, data::Command::PlaylistDelete(id)),
            Dialog::ClearCache => self.send(cx, data::Command::ClearCache),
            Dialog::Logout => self.send(cx, data::Command::Logout),
            Dialog::BookmarkThreshold => {
                if let Ok(minutes) = input.trim().parse::<u64>() {
                    if minutes > 0 {
                        self.send(
                            cx,
                            data::Command::Setting("resume_minutes".into(), json!(minutes)),
                        );
                    }
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
                    self.open(cx, Dialog::ConfirmSend(id, 0, title));
                }
                return;
            }
            Dialog::Topics(chat, name) => {
                self.open(
                    cx,
                    Dialog::ConfirmSend(chat, id, format!("{name} · {title}")),
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
                let mut query = state.page.query.clone();
                query.source = if index == 0 {
                    String::new()
                } else {
                    state
                        .sources
                        .iter()
                        .filter(|s| s.selected)
                        .nth(index - 1)
                        .map(|s| format!("{}:{}", if s.profile { "profile" } else { "chat" }, s.id))
                        .unwrap_or_default()
                };
                self.text(cx, ids!(source_filter), &title);
                self.send(cx, data::Command::Query(query, 0));
            }
            Dialog::Sort => {
                let mut query = state.page.query.clone();
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
        self.send(cx, data::Command::Visible(false));
    }
    fn handle_foreground(&mut self, cx: &mut Cx) {
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
            let mut query = self.snapshot().page.query.clone();
            query.search = self.ui.text_input(cx, ids!(search)).text();
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
        if self.dialog != Dialog::None {
            self.close_dialog(cx);
            true
        } else if self.full_player {
            self.full_player = false;
            self.visible(cx, ids!(full_player), false);
            self.visible(cx, ids!(main), true);
            self.send(cx, data::Command::ObserveClock(false));
            true
        } else if matches!(
            self.snapshot().page.query.screen,
            data::Screen::Profile(_)
                | data::Screen::Playlist(_)
                | data::Screen::Queue
                | data::Screen::OnDevice
        ) {
            self.navigate(cx, data::Screen::Music);
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
            (ids!(queue), data::Screen::Queue),
        ] {
            if self.click(cx, actions, path) {
                self.navigate(cx, screen);
                return;
            }
        }
        if self.click(cx, actions, ids!(settings)) || self.click(cx, actions, ids!(rail_settings)) {
            self.open(cx, Dialog::Settings);
        }
        if self.click(cx, actions, ids!(panel_close)) {
            self.close_dialog(cx);
        }
        if self.click(cx, actions, ids!(player_close)) {
            self.full_player = false;
            self.visible(cx, ids!(full_player), false);
            self.visible(cx, ids!(main), true);
            self.send(cx, C::ObserveClock(false));
        }
        if self.click(cx, actions, ids!(back)) {
            self.navigate(cx, data::Screen::Music);
        }
        if self.click(cx, actions, ids!(refresh)) {
            self.send(cx, C::Refresh);
        }
        if self.click(cx, actions, ids!(import)) || self.click(cx, actions, ids!(settings_import)) {
            self.send(cx, C::PickFiles);
        }
        if self.click(cx, actions, ids!(play_all)) || self.click(cx, actions, ids!(profile_play)) {
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
        if self.click(cx, actions, ids!(order)) {
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
            (ids!(speed_slow), 0.75),
            (ids!(speed_normal), 1.),
            (ids!(speed_fast), 1.5),
            (ids!(speed_double), 2.),
        ] {
            if self.click(cx, actions, id) {
                self.send(cx, C::Speed(speed));
            }
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
        if self.click(cx, actions, ids!(add_chats)) {
            self.send(cx, C::Discover(String::new(), self.folder));
            self.open(cx, Dialog::Chats);
        }
        if self.click(cx, actions, ids!(directory_folder)) {
            self.open(cx, Dialog::Folders(self.dialog == Dialog::Recipients));
        }
        if self.click(cx, actions, ids!(add_profile)) {
            self.open(cx, Dialog::Profile);
        }
        if self.click(cx, actions, ids!(create_playlist)) {
            self.open(cx, Dialog::NewPlaylist);
        }
        if self.click(cx, actions, ids!(profile_connect)) {
            if let data::Screen::Profile(id) = state.page.query.screen {
                if let Some(source) = state.sources.iter().find(|s| s.profile && s.id == id) {
                    self.send(cx, C::ConnectSource(source.clone(), !source.selected));
                }
            }
        }
        if self.click(cx, actions, ids!(select_mode)) {
            self.selecting = !self.selecting;
            self.selection.clear();
            self.selection_ui(cx);
            self.visible(cx, ids!(selection_controls), self.selecting);
        }
        if self.click(cx, actions, ids!(selection_close)) {
            self.selecting = false;
            self.selection.clear();
            self.selection_ui(cx);
            self.visible(cx, ids!(selection_controls), false);
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
            (ids!(settings_sharing_sender), "sharing_sender"),
            (ids!(settings_sharing_caption), "sharing_caption"),
        ] {
            if self.click(cx, actions, id) {
                self.send(
                    cx,
                    C::Setting(key.into(), json!(state.settings[key] != true)),
                );
            }
        }
        if self.click(cx, actions, ids!(settings_theme)) {
            self.send(
                cx,
                C::Setting(
                    "theme".into(),
                    json!(if state.settings["theme"] == "light" {
                        "dark"
                    } else {
                        "light"
                    }),
                ),
            );
        }
        if self.click(cx, actions, ids!(settings_language)) {
            self.send(
                cx,
                C::Setting(
                    "language".into(),
                    json!(if state.settings["language"] == "ru" {
                        "en"
                    } else {
                        "ru"
                    }),
                ),
            );
        }
        if self.click(cx, actions, ids!(settings_minutes)) {
            self.open(cx, Dialog::BookmarkThreshold);
        }
        if self.click(cx, actions, ids!(settings_clear)) {
            self.open(cx, Dialog::ClearCache);
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
            if self.click(cx, actions, ids!(track_play)) {
                self.send(
                    cx,
                    if let Some(position) = track.queue_position {
                        C::PlayQueue(position)
                    } else {
                        C::Play(track.id.clone())
                    },
                );
                self.close_dialog(cx);
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
        let catalog = self.ui.widget(cx, ids!(catalog));
        let list = catalog.portal_list(cx, ids!(list));
        for (index, item) in list.items_with_actions(actions) {
            match state.page.query.screen {
                data::Screen::Sources => {
                    if item.button(cx, ids!(open)).clicked(actions) {
                        if index == 0 {
                            self.send(cx, C::OpenProfile("me".into()));
                        } else if let Some(source) = state.sources.get(index - 1) {
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
                    if item.glass_button(cx, ids!(select)).clicked(actions) {
                        if let Some(source) =
                            index.checked_sub(1).and_then(|i| state.sources.get(i))
                        {
                            self.send(cx, C::ConnectSource(source.clone(), !source.selected));
                        }
                    }
                }
                data::Screen::Playlists => {
                    if let Some(p) = state.playlists.get(index) {
                        if item.button(cx, ids!(open)).clicked(actions) {
                            self.navigate(cx, data::Screen::Playlist(p.id));
                        }
                        if item.button(cx, ids!(more)).clicked(actions) {
                            self.open(cx, Dialog::PlaylistActions(p.id));
                        }
                    }
                }
                _ => {
                    if let Some(track) = index
                        .checked_sub(state.page.offset)
                        .and_then(|i| state.page.tracks.get(i))
                    {
                        if item.button(cx, ids!(song)).clicked(actions) {
                            if self.selecting {
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
    }
}

impl AppMain for App {
    fn script_mod(vm: &mut ScriptVm) -> ScriptValue {
        makepad_widgets::script_mod(vm);
        if base_theme(vm.cx_mut()) == BaseTheme::Light {
            script_eval!(vm,{mod.theme.color_bg_app = #xf9fafc; mod.theme.color_text = #x1d3032; mod.theme.color_text_meta = #x596575; mod.theme.color_primary = #x137b8a;});
        } else {
            script_eval!(vm,{mod.theme.color_bg_app = #x080a0e; mod.theme.color_text = #xf1f6f3; mod.theme.color_text_meta = #xb0b8c3; mod.theme.color_primary = #x58c9d2;});
        }
        crate::ui::script_mod(vm);
        self::script_mod(vm)
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event) {
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
            self.layout(cx);
            let safe = change.new_geom.safe_area_insets;
            let top = safe.top;
            let bottom = safe.bottom;
            let mut root = self.ui.widget(cx, ids!(root));
            let padding = Inset {
                top,
                bottom,
                ..Default::default()
            };
            script_apply_eval!(cx,root,{padding:#(padding)});
        }
        if let Event::WindowCloseRequested(request) = event {
            if !self.closed {
                request.accept_close.set(false);
                let state = self.snapshot();
                if state.playback.playing || state.downloads > 0 || state.busy {
                    self.ui.window(cx, ids!(main_window)).minimize(cx);
                    self.send(cx, data::Command::Visible(false));
                } else {
                    self.begin_quit(cx);
                }
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
            if self.dialog != Dialog::None {
                let area = self.ui.widget(cx, ids!(panel)).area();
                if area.is_valid(cx) && !area.rect(cx).contains(pointer) {
                    self.close_dialog(cx);
                    return;
                }
            }
        }
        self.match_event(cx, event);
        if matches!(event, Event::LiveEdit | Event::ScriptReapply) {
            self.localize(cx);
            self.sync_ui(cx);
            self.configure_dialog(cx);
            self.layout(cx);
            self.selection_ui(cx);
        }
        if self.dialog != Dialog::None
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
