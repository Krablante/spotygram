use crate::{model as data, runtime};
use makepad_widgets::*;
use std::collections::HashSet;

script_mod! {
    use mod.prelude.widgets.*
    use mod.widgets.*
    use mod.widgets.glass.*

    mod.spotygram = {}
    mod.spotygram.Title = Label {
        width: Fill height: 34 padding:0 max_lines: 1 text_overflow: Ellipsis
        draw_text.color: theme.color_text
        draw_text.text_style: theme.font_bold{font_size: 24 line_spacing:1.0}
    }
    mod.spotygram.Body = Label {
        width: Fill height: 22 padding:0 max_lines: 1 text_overflow: Ellipsis
        draw_text +:{color:theme.color_text text_style:theme.font_regular{font_size:14 line_spacing:1.0}}
    }
    mod.spotygram.Caption = Label {
        width: Fill height: 18 padding:0 max_lines: 1 text_overflow: Ellipsis
        draw_text +:{color:theme.color_text_meta text_style:theme.font_regular{font_size:12 line_spacing:1.0}}
    }
    mod.spotygram.Prism = View {
        width: Fill height: Fill show_bg: true
        draw_bg +: {
            color: theme.color_bg_app
            pixel: fn() {
                let p = self.pos;
                let a = exp(-dot(p-vec2(0.0,0.2),p-vec2(0.0,0.2))*18.0);
                let b = exp(-dot(p-vec2(1.0,0.24),p-vec2(1.0,0.24))*22.0);
                let c = exp(-dot(p-vec2(0.0,1.0),p-vec2(0.0,1.0))*15.0);
                let d = exp(-dot(p-vec2(1.0,1.0),p-vec2(1.0,1.0))*20.0);
                let base=mix(self.color.rgb,vec3(0.28,0.66,0.69),a*0.20);
                let warm=mix(base,vec3(0.93,0.68,0.50),b*0.16);
                let blue=mix(warm,vec3(0.34,0.53,0.81),c*0.18);
                return vec4(mix(blue,vec3(0.87,0.48,0.63),d*0.16),1.0);
            }
        }
    }
    mod.spotygram.Glass = glass.GlassButton {
        height: 48
        draw_glass +:{shadow_color:#x0000001c shadow_radius:8.0 shadow_offset:vec2(0.0,2.0)}
        draw_text +: {color: theme.color_text text_style: theme.font_bold{font_size: 13}}
    }
    mod.spotygram.Icon = mod.spotygram.Glass {width:48 padding:0}
    // Cut buttons stay in their containing glass surface's draw list.
    mod.spotygram.InlineGlass = glass.Button{height:48 draw_text +:{color:theme.color_text color_hover:theme.color_text color_down:theme.color_text color_focus:theme.color_text text_style:theme.font_bold{font_size:13}} draw_bg +:{border_radius:24.0}}
    mod.spotygram.InlineIcon = mod.spotygram.InlineGlass{width:48 padding:0}
    mod.spotygram.DrawMediaBar = set_type_default() do #(DrawMediaBar::script_shader(vm)){
        ..mod.draw.DrawQuad
        progress:0.0
        fill:uniform(theme.color_primary)
        ink:uniform(theme.color_text)
        track:uniform(mix(theme.color_bg_app,theme.color_text,0.2))
        pixel:fn(){
            let sdf=Sdf2d.viewport(self.pos*self.rect_size);
            let y=self.rect_size.y*0.5;
            let span=max(self.rect_size.x-12.0,1.0);
            let x=6.0+clamp(self.progress,0.0,1.0)*span;
            sdf.box(6.0,y-2.0,span,4.0,2.0);sdf.fill(self.track);
            sdf.box(6.0,y-2.0,max(x-6.0,0.01),4.0,2.0);sdf.fill(self.fill);
            sdf.circle(x,y,5.0);sdf.fill(self.ink);
            return sdf.result;
        }
    }
    mod.spotygram.Slider = #(MediaSlider::register_widget(vm)){
        width:Fill height:32 min:0.0 max:1.0 step:0.0
        draw_bg:mod.spotygram.DrawMediaBar{}
    }
    mod.spotygram.Flat = ButtonFlatter {
        height: 44 width: Fill
        margin:0 padding:Inset{left:12 right:12 top:5 bottom:5}
        label_walk:Walk{width:Fill height:Fit} label_align:Align{x:0.0}
        draw_text +: {color: theme.color_text text_style:theme.font_regular{font_size:13 line_spacing:1.0}}
    }
    mod.spotygram.Nav = mod.spotygram.Glass{width:Fill padding:Inset{left:4 right:4 top:10 bottom:10} draw_text.text_style.font_size:11}
    mod.spotygram.Input = TextInput {
        width: Fill height: 48
        padding: Inset{left: 16, right: 14, top: 13, bottom: 10}
        draw_bg +: {border_radius: 24.0 border_size: 1.0}
        draw_text +: {color:theme.color_text color_empty:theme.color_text_meta color_empty_hover:theme.color_text text_style.font_size: 14}
    }
    mod.spotygram.Cover = RoundedView {
        width: 46 height: 46
        flow: Overlay align: Center
        draw_bg +: {
            border_radius: 10.0
            color: #x3b637c
            pixel: fn() {
                let sdf = Sdf2d.viewport(self.pos * self.rect_size);
                sdf.box(0.0,0.0,self.rect_size.x,self.rect_size.y,10.0);
                sdf.fill(mix(vec4(0.22,0.43,0.43,1.0),vec4(0.42,0.62,0.82,1.0),(self.pos.x+self.pos.y)*0.5));
                return sdf.result;
            }
        }
        note := Label {text: "♫" draw_text.color: #xd5e6e2 draw_text.text_style.font_size: 26}
        art := Image {width: Fill height: Fill fit: ImageFit.CropToFill downscale_to_drawn_size:true draw_bg +:{border_radius:10.0}}
    }

    mod.spotygram.CatalogBase = #(Catalog::register_widget(vm))
    mod.spotygram.ChoicesBase = #(Choices::register_widget(vm))
    mod.spotygram.Choices = set_type_default() do mod.spotygram.ChoicesBase {
        width:Fill height:300 flow:Down
        list := PortalList{width:Fill height:Fill scroll_bar:ScrollBar{}
            Choice := CachedView{width:Fill height:58 flow:Right spacing:10 align:Align{y:0.5}
                choice := mod.spotygram.Flat{width:Fill text:""}
            }
        }
    }
    mod.spotygram.Catalog = set_type_default() do mod.spotygram.CatalogBase {
        width: Fill height: Fill
        list := PortalList {
            width: Fill height: Fill
            auto_tail: false
            scroll_bar: ScrollBar{}
            Song := CachedView {
                width: Fill height: 68
                flow: Right spacing: 10 align: Align{y:0.5}
                padding: Inset{left:6,right:4,top:8,bottom:8}
                cover := mod.spotygram.Cover{}
                selection := Label{visible:false width:22 text:"✓" draw_text.color:theme.color_primary draw_text.text_style.font_size:18}
                View {
                    width: Fill height: Fill flow: Overlay
                    song := ButtonFlatter {width:Fill height:Fill text:""}
                    View {
                        width: Fill height: Fill flow: Down spacing:4 align:Align{y:0.5}
                        title := mod.spotygram.Body {draw_text.text_style:theme.font_bold{font_size:15}}
                        artist := mod.spotygram.Caption{}
                    }
                }
                duration := mod.spotygram.Caption {width:42 text:"0:00"}
                favorite := ButtonFlatter {width:32 height:44 padding:0 text:"♡" draw_text +:{color:#xffa2a2 text_style.font_size:24}}
                more := ButtonFlatter {width:32 height:44 padding:0 text:"⋮" draw_text +:{color:theme.color_text text_style.font_size:24}}
            }
            Source := CachedView {
                width: Fill height: 76 flow:Right spacing:12 align:Align{y:0.5}
                padding: Inset{left:8,right:6,top:10,bottom:10}
                cover := mod.spotygram.Cover{}
                View {
                    width:Fill height:Fill flow:Overlay
                    open := ButtonFlatter{width:Fill height:Fill text:""}
                    View{width:Fill height:Fill flow:Down spacing:5 align:Align{y:0.5}
                        title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:15}}
                        subtitle := mod.spotygram.Caption{}
                    }
                }
                select := mod.spotygram.Icon{text:"+"}
            }
            Playlist := CachedView {
                width:Fill height:76 flow:Right spacing:12 align:Align{y:0.5}
                cover := mod.spotygram.Cover{}
                View{width:Fill height:Fill flow:Overlay
                    open := ButtonFlatter{width:Fill height:Fill text:""}
                    View{width:Fill height:Fill flow:Down spacing:5 align:Align{y:0.5}
                        title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:15}}
                        subtitle := mod.spotygram.Caption{}
                    }
                }
                more := ButtonFlatter{width:40 height:48 text:"⋮" draw_text +:{text_style.font_size:24}}
            }
            Empty := CachedView {
                width:Fill height:250 flow:Down align:Center spacing:12
                Image{width:112 height:112 fit:ImageFit.Smallest src:crate_resource("self:resources/spotygram_mark.png")}
                title := mod.spotygram.Body{align:Center text:"Your next favorite song starts here."}
                subtitle := mod.spotygram.Caption{align:Center text:"Import audio files or connect Telegram."}
            }
        }
    }

    mod.spotygram.RootView = mod.spotygram.Prism {
        flow: Overlay
        main := View {
            width: Fill height: Fill flow: Right
            rail := View {
                width:196 height:Fill padding:Inset{left:18,right:12,top:28,bottom:20}
                flow:Down spacing:12
                View{width:Fill height:48 flow:Right spacing:8 align:Align{y:0.5}
                    Image{width:32 height:32 fit:ImageFit.Smallest src:crate_resource("self:resources/spotygram_mark.png")}
                    Label{text:"spotygram" draw_text.color:theme.color_text draw_text.text_style:theme.font_bold{font_size:19}}
                }
                rail_music := mod.spotygram.Glass{width:Fill text:"Music"}
                rail_favorites := mod.spotygram.Glass{width:Fill text:"Favorites"}
                rail_playlists := mod.spotygram.Glass{width:Fill text:"Playlists"}
                rail_sources := mod.spotygram.Glass{width:Fill text:"Sources"}
                Filler{}
                preview_label := Label{text:"RUST PREVIEW" draw_text.color:theme.color_text_meta draw_text.text_style.font_size:10}
                rail_settings := mod.spotygram.Glass{width:Fill text:"Settings"}
            }
            content := View {
                width:Fill height:Fill flow:Down spacing:10
                padding:Inset{left:18,right:18,top:20,bottom:14}
                header := View{width:Fill height:48 flow:Right align:Align{y:0.5}
                    brand := Label{width:Fill text:"spotygram" draw_text.color:theme.color_text draw_text.text_style:theme.font_bold{font_size:19}}
                    header_status := mod.spotygram.Caption{width:Fill text:"Rust preview"}
                    settings := mod.spotygram.Icon{text:"" padding:12 icon_walk:Walk{width:24 height:24} draw_icon +:{color:theme.color_text svg:crate_resource("self:resources/settings.svg")}}
                }
                View{width:Fill height:48 flow:Right align:Align{y:0.5} spacing:8
                    back := mod.spotygram.Icon{visible:false text:"‹"}
                    heading := mod.spotygram.Title{text:"Music"}
                    count := mod.spotygram.Caption{width:Fit text:"0 tracks"}
                    refresh := mod.spotygram.Icon{text:"↻"}
                }
                search := mod.spotygram.Input{empty_text:"Search music"}
                music_controls := View{width:Fill height:Fit flow:Down spacing:8
                    View{width:Fill height:48 flow:Right spacing:8
                        source_filter := mod.spotygram.Glass{width:Fill text:"All sources"}
                        on_device := mod.spotygram.Icon{text:"↓"}
                        sort := mod.spotygram.Icon{text:"A↕"}
                    }
                    View{width:Fill height:48 flow:Right spacing:8
                        play_all := mod.spotygram.Glass{width:Fill text:"▶   Play all"}
                        select_mode := mod.spotygram.Icon{text:"" padding:12 icon_walk:Walk{width:24 height:24} draw_icon +:{color:theme.color_text svg:crate_resource("self:resources/select.svg")}}
                        import := mod.spotygram.Icon{text:"+"}
                    }
                }
                collection_controls := View{width:Fill height:48 flow:Right spacing:8 visible:false
                    create_playlist := mod.spotygram.Glass{width:Fill text:"New playlist"}
                    add_chats := mod.spotygram.Glass{width:Fill text:"Add chats"}
                    add_profile := mod.spotygram.Glass{width:Fill text:"Add profile"}
                }
                profile_controls := View{width:Fill height:48 flow:Right spacing:8 visible:false
                    profile_connect := mod.spotygram.Glass{width:Fill text:"Add to library"}
                    profile_play := mod.spotygram.Glass{width:Fill text:"▶   Play"}
                }
                selection_controls := View{width:Fill height:48 flow:Right spacing:8 visible:false
                    selection_count := mod.spotygram.Caption{text:"Selected"}
                    selection_playlist := mod.spotygram.Icon{text:"≡+"}
                    selection_download := mod.spotygram.Icon{text:"↓"}
                    selection_close := mod.spotygram.Icon{text:"×"}
                }
                notice := mod.spotygram.Caption{visible:false height:Fit max_lines:3}
                catalog := mod.spotygram.Catalog{}
                player_dock := View{width:Fill height:74 flow:Overlay visible:false
                    glass.Layer{width:Fill height:Fill
                        glass.Card{width:Fill height:Fill flow:Right spacing:12 padding:12 align:Align{y:0.5}
                            mini_cover := mod.spotygram.Cover{width:46 height:46}
                            View{width:Fill height:Fill flow:Overlay
                                show_player := ButtonFlatter{width:Fill height:Fill text:""}
                                View{width:Fill height:Fill flow:Down spacing:4 align:Align{y:0.5}
                                    mini_title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:15}}
                                    mini_artist := mod.spotygram.Caption{}
                                }
                            }
                            previous := mod.spotygram.InlineIcon{text:"|‹"}
                            mini_play := mod.spotygram.InlineIcon{text:"▶"}
                            next := mod.spotygram.InlineIcon{text:"›|"}
                        }
                    }
                }
                bottom_nav := View{width:Fill height:58 flow:Right spacing:6
                    nav_music := mod.spotygram.Nav{text:"Music"}
                    nav_favorites := mod.spotygram.Nav{text:"Favorites"}
                    nav_playlists := mod.spotygram.Nav{text:"Playlists"}
                    nav_sources := mod.spotygram.Nav{text:"Sources"}
                }
            }
        }
        full_player := mod.spotygram.Prism{visible:false flow:Down padding:24 spacing:20
            View{width:Fill height:48 flow:Right align:Align{y:0.5}
                player_close := mod.spotygram.Icon{text:"↓"}
                playing_label := Label{width:Fill text:"NOW PLAYING" align:Center draw_text.color:theme.color_text_meta draw_text.text_style.font_size:11}
                player_more := mod.spotygram.Icon{text:"⋮"}
            }
            player_columns := ScrollYView{width:Fill height:Fill flow:Right spacing:32 align:Center
                player_art := mod.spotygram.Cover{width:320 height:320
                    note +:{draw_text.text_style.font_size:100}
                }
                player_details := View{width:Fill height:Fit flow:Down spacing:18
                    View{width:Fill height:Fit flow:Down spacing:8
                        player_title := mod.spotygram.Title{text:""}
                        player_artist := mod.spotygram.Body{text:""}
                    }
                    seek := mod.spotygram.Slider{min:0.0 max:1.0 step:0.0}
                    View{width:Fill height:20 flow:Right
                        elapsed := mod.spotygram.Caption{width:Fill text:"0:00"}
                        duration := mod.spotygram.Caption{width:Fit text:"0:00"}
                    }
                    View{width:Fill height:64 flow:Right spacing:12 align:Align{y:0.5}
                        order := mod.spotygram.Icon{text:"↕"}
                        player_previous := mod.spotygram.Icon{text:"|‹"}
                        player_play := mod.spotygram.Glass{width:Fill height:64 text:"▶"}
                        player_next := mod.spotygram.Icon{text:"›|"}
                        repeat := mod.spotygram.Icon{text:"↻"}
                    }
                    View{width:Fill height:48 flow:Right spacing:10
                        player_save := mod.spotygram.Glass{width:Fill padding:8 text:"↓  Save"}
                        speed := mod.spotygram.Glass{width:64 text:"1×"}
                        queue := mod.spotygram.Glass{width:Fill text:"≡  Queue"}
                    }
                    volume_row := View{width:Fill height:44 flow:Right align:Align{y:0.5} spacing:8
                        mute := mod.spotygram.Icon{text:"♪"}
                        volume := mod.spotygram.Slider{min:0.0 max:100.0 step:1.0}
                        volume_label := mod.spotygram.Caption{width:56 text:"100%"}
                    }
                }
            }
        }
        overlay := View{visible:false width:Fill height:Fill flow:Overlay align:Center
            scrim := SolidView{width:Fill height:Fill draw_bg.color:#x00000055}
            glass.Layer{width:Fill height:Fill align:Center
                panel := glass.Card{width:500 height:Fit flow:Down spacing:14 padding:22
                    draw_bg +:{blur_level:4.5 tint_color:theme.color_bg_app tint_alpha:0.88}
                    View{width:Fill height:48 flow:Right align:Align{y:0.5} spacing:10
                    panel_title := mod.spotygram.Title{text:"Settings"}
                        panel_close := mod.spotygram.InlineIcon{text:"×"}
                    }
                    panel_error := mod.spotygram.Body{visible:false height:Fit max_lines:3 draw_text.color:#xff8e8e}
                    panel_scroll := ScrollYView{width:Fill height:Fit flow:Down spacing:12
                        panel_message := mod.spotygram.Body{height:Fit max_lines:8 visible:false}
                        directory_folder := mod.spotygram.Flat{visible:false text:"All chats"}
                        panel_input_wrap := View{visible:false width:Fill height:Fit
                            panel_input := mod.spotygram.Input{}
                        }
                        auth_mark := View{visible:false width:Fill height:120 align:Center
                            Image{width:120 height:120 fit:ImageFit.Smallest src:crate_resource("self:resources/spotygram_mark.png")}
                        }
                        auth_qr_area := View{visible:false width:Fill height:220 align:Center
                            auth_qrcode := Image{width:220 height:220 fit:ImageFit.Smallest}
                        }
                        auth_actions := View{visible:false width:Fill height:Fit flow:Down spacing:8
                            auth_submit := mod.spotygram.InlineGlass{width:Fill text:"Continue"}
                            auth_qr := mod.spotygram.Flat{text:"Sign in with QR code"}
                            auth_phone := mod.spotygram.Flat{text:"Use another phone number"}
                            auth_resend := mod.spotygram.Flat{text:"Resend code"}
                        }
                        settings_body := View{visible:false width:Fill height:Fit flow:Down spacing:8
                            settings_import := mod.spotygram.InlineGlass{width:Fill text:"Import audio files"}
                            settings_account := mod.spotygram.InlineGlass{width:Fill text:"Connect Telegram"}
                            settings_theme := mod.spotygram.Flat{text:"Appearance: System"}
                            settings_language := mod.spotygram.Flat{text:"Language: English"}
                            settings_duplicates := mod.spotygram.Flat{text:"Hide duplicates: On"}
                            settings_bookmarks := mod.spotygram.Flat{text:"Resume long recordings: On"}
                            settings_minutes := mod.spotygram.Flat{text:"Bookmark threshold: 30 minutes"}
                            settings_wifi := mod.spotygram.Flat{text:"Downloads over Wi-Fi only: Off"}
                            settings_discard := mod.spotygram.Flat{text:"Do not keep played tracks: Off"}
                            settings_storage := mod.spotygram.Caption{text:"On device"}
                            settings_clear := mod.spotygram.Flat{text:"Clear music cache"}
                            settings_transfers := mod.spotygram.Flat{text:"Cancel downloads"}
                            settings_sync := mod.spotygram.Caption{height:Fit max_lines:3 text:"Library sync"}
                            settings_sharing_sender := mod.spotygram.Flat{text:"Keep original sender: Off"}
                            settings_sharing_caption := mod.spotygram.Flat{text:"Keep original caption: Off"}
                            settings_update := mod.spotygram.Flat{text:"Check Rust preview updates"}
                            settings_about := mod.spotygram.Caption{height:Fit max_lines:3 text:"spotygram rust · 0.1.0\nA personal testing build. Local data is separate from the stable app."}
                            settings_quit := mod.spotygram.Flat{text:"Quit spotygram rust"}
                        }
                        track_body := View{visible:false width:Fill height:Fit flow:Down spacing:4
                            track_header := View{width:Fill height:64 flow:Right spacing:12 align:Align{y:0.5}
                                track_cover := mod.spotygram.Cover{}
                                View{width:Fill height:Fit flow:Down spacing:6
                                    track_title := mod.spotygram.Body{}
                                    track_artist := mod.spotygram.Caption{}
                                }
                            }
                            track_profile_member := mod.spotygram.InlineGlass{width:Fill text:"To profile"}
                            track_profile_hint := mod.spotygram.Caption{visible:false height:Fit max_lines:3 text:"This local recording will be uploaded to your Telegram profile."}
                            track_play := mod.spotygram.Flat{text:"Play"}
                            track_favorite := mod.spotygram.Flat{text:"Add to favorites"}
                            track_playlist := mod.spotygram.Flat{text:"Add to playlist"}
                            track_queue := mod.spotygram.Flat{text:"Add to queue"}
                            track_move_up := mod.spotygram.Flat{text:"Move up"}
                            track_move_down := mod.spotygram.Flat{text:"Move down"}
                            track_remove_queue := mod.spotygram.Flat{text:"Remove from queue"}
                            track_download := mod.spotygram.Flat{text:"Save on device"}
                            track_send := mod.spotygram.Flat{text:"Send to Telegram chat"}
                            track_profile := mod.spotygram.Flat{text:"Open Telegram profile"}
                            track_original := mod.spotygram.Flat{text:"Open original message"}
                            track_remove_playlist := mod.spotygram.Flat{text:"Remove from this playlist"}
                            track_delete := mod.spotygram.Flat{text:"Remove local copy"}
                        }
                        choices_body := mod.spotygram.Choices{visible:false}
                        speed_body := View{visible:false width:Fill height:Fit flow:Down spacing:12
                            speed_value := mod.spotygram.Title{text:"1×" align:Center}
                            speed_slider := mod.spotygram.Slider{min:0.3 max:4.0 step:0.05}
                            View{width:Fill height:48 flow:Right spacing:6
                                speed_slow := mod.spotygram.InlineGlass{width:Fill text:"0.75×"}
                                speed_normal := mod.spotygram.InlineGlass{width:Fill text:"1×"}
                                speed_fast := mod.spotygram.InlineGlass{width:Fill text:"1.5×"}
                                speed_double := mod.spotygram.InlineGlass{width:Fill text:"2×"}
                            }
                        }
                    }
                    panel_confirm := mod.spotygram.InlineGlass{visible:false width:Fill text:"Confirm"}
                }
            }
        }
    }
}

#[derive(Script, ScriptHook)]
#[repr(C)]
pub struct DrawMediaBar {
    #[deref]
    draw_super: DrawQuad,
    #[live]
    progress: f32,
}

/// A music slider: a tap sets an absolute value and never opens a numeric keyboard.
#[derive(Script, ScriptHook, Widget)]
pub struct MediaSlider {
    #[uid]
    uid: WidgetUid,
    #[source]
    source: ScriptObjectRef,
    #[walk]
    walk: Walk,
    #[redraw]
    #[live]
    draw_bg: DrawMediaBar,
    #[live]
    min: f64,
    #[live(1.0)]
    max: f64,
    #[live]
    step: f64,
    #[rust]
    value: f64,
    #[rust]
    dragging: bool,
}

impl MediaSlider {
    fn assign(&mut self, cx: &mut Cx, value: f64) {
        let value = if self.step > 0.0 {
            self.min + ((value - self.min) / self.step).round() * self.step
        } else {
            value
        };
        let value = value.clamp(self.min, self.max.max(self.min));
        if self.value != value {
            self.value = value;
            self.draw_bg.redraw(cx);
        }
    }
    fn pointer(&mut self, cx: &mut Cx, position: Vec2d, rect: Rect) {
        let fraction =
            ((position.x - rect.pos.x - 6.0) / (rect.size.x - 12.0).max(1.0)).clamp(0.0, 1.0);
        self.assign(cx, self.min + (self.max - self.min) * fraction);
    }
}

impl Widget for MediaSlider {
    fn draw_walk(&mut self, cx: &mut Cx2d, _: &mut Scope, walk: Walk) -> DrawStep {
        self.draw_bg.progress = ((self.value - self.min) / (self.max - self.min).max(f64::EPSILON))
            .clamp(0.0, 1.0) as f32;
        self.draw_bg.draw_walk(cx, walk);
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, _: &mut Scope) {
        match event.hits(cx, self.draw_bg.area()) {
            Hit::FingerDown(e) if e.device.is_primary_hit() => {
                self.dragging = true;
                cx.claim_finger_drag(e.digit_id);
                cx.set_key_focus(self.draw_bg.area());
                self.pointer(cx, e.abs, e.rect);
                cx.widget_action(self.uid, SliderAction::Slide(self.value));
            }
            Hit::FingerMove(e) if self.dragging => {
                self.pointer(cx, e.abs, e.rect);
                cx.widget_action(self.uid, SliderAction::Slide(self.value));
            }
            Hit::FingerUp(e) if self.dragging => {
                self.pointer(cx, e.abs, e.rect);
                self.dragging = false;
                cx.widget_action(self.uid, SliderAction::EndSlide(self.value));
            }
            Hit::KeyDown(e) => {
                let increment = if self.step > 0.0 {
                    self.step
                } else {
                    (self.max - self.min) * 0.01
                };
                let value = match e.key_code {
                    KeyCode::ArrowLeft | KeyCode::ArrowDown => self.value - increment,
                    KeyCode::ArrowRight | KeyCode::ArrowUp => self.value + increment,
                    KeyCode::Home => self.min,
                    KeyCode::End => self.max,
                    _ => return,
                };
                self.assign(cx, value);
                cx.widget_action(self.uid, SliderAction::EndSlide(self.value));
            }
            _ => {}
        }
    }
    fn snapshot_value(&self, _: &Cx) -> Option<String> {
        Some(format!("{:.3}", self.value))
    }
}

impl MediaSliderRef {
    pub fn set_value(&self, cx: &mut Cx, value: f64) {
        if let Some(mut slider) = self.borrow_mut() {
            if !slider.dragging && value.is_finite() {
                slider.assign(cx, value);
            }
        }
    }
    pub fn slided(&self, actions: &Actions) -> Option<f64> {
        match actions.find_widget_action(self.widget_uid())?.cast() {
            SliderAction::Slide(value) | SliderAction::EndSlide(value) => Some(value),
            _ => None,
        }
    }
    pub fn end_slide(&self, actions: &Actions) -> Option<f64> {
        match actions.find_widget_action(self.widget_uid())?.cast() {
            SliderAction::EndSlide(value) => Some(value),
            _ => None,
        }
    }
}

#[derive(Script, ScriptHook, Widget)]
pub struct Catalog {
    #[deref]
    view: View,
    #[rust]
    requested: Option<(u64, usize)>,
    #[rust]
    pub selection: HashSet<String>,
    #[rust]
    artwork_ids: Vec<String>,
}

impl Widget for Catalog {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        let client = runtime::start();
        let state = client.snapshot.load_full();
        let screen = &state.page.query.screen;
        let count = match screen {
            data::Screen::Sources => state.sources.len() + 1,
            data::Screen::Playlists => state.playlists.len(),
            _ => state.page.total,
        };
        let mut need_page = None;
        let mut artwork_ids = Vec::new();
        while let Some(step) = self.view.draw_walk(cx, scope, walk).step() {
            if let Some(mut list) = step.as_portal_list().borrow_mut() {
                list.set_item_range(cx, 0, count.max(1));
                while let Some(index) = list.next_visible_item(cx) {
                    if index >= count.max(1) {
                        continue;
                    }
                    if count == 0 {
                        let item = list.item(cx, index, id!(Empty));
                        item.label(cx, ids!(title)).set_text(
                            cx,
                            &crate::i18n::tr(
                                &state.language,
                                "Your next favorite song starts here.",
                            ),
                        );
                        item.label(cx, ids!(subtitle)).set_text(
                            cx,
                            &crate::i18n::tr(
                                &state.language,
                                "Import audio files or connect Telegram.",
                            ),
                        );
                        item.draw_all_unscoped(cx);
                        continue;
                    }
                    match screen {
                        data::Screen::Sources => {
                            let item = list.item(cx, index, id!(Source));
                            if index == 0 {
                                item.label(cx, ids!(title))
                                    .set_text(cx, &crate::i18n::tr(&state.language, "My profile"));
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &crate::i18n::tr(&state.language, "Music in Telegram"),
                                );
                                item.widget(cx, ids!(select)).set_visible(cx, false);
                            } else if let Some(source) = state.sources.get(index - 1) {
                                item.label(cx, ids!(title)).set_text(cx, &source.title);
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &format!(
                                        "{} · {}",
                                        crate::i18n::tracks(&state.language, source.count),
                                        crate::i18n::tr(
                                            &state.language,
                                            if source.profile { "Profile" } else { "Chat" }
                                        )
                                    ),
                                );
                                item.widget(cx, ids!(select)).set_visible(cx, true);
                                item.widget(cx, ids!(select))
                                    .set_text(cx, if source.selected { "−" } else { "+" });
                                set_image(&item, cx, ids!(cover.art), &source.photo);
                            }
                            item.draw_all_unscoped(cx);
                        }
                        data::Screen::Playlists => {
                            let item = list.item(cx, index, id!(Playlist));
                            if let Some(playlist) = state.playlists.get(index) {
                                item.label(cx, ids!(title)).set_text(cx, &playlist.name);
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &crate::i18n::tracks(&state.language, playlist.count),
                                );
                            }
                            item.draw_all_unscoped(cx);
                        }
                        _ => {
                            let item = list.item(cx, index, id!(Song));
                            if let Some(track) = index
                                .checked_sub(state.page.offset)
                                .and_then(|i| state.page.tracks.get(i))
                            {
                                if track.art.is_empty() && !track.imported() {
                                    artwork_ids.push(track.id.clone());
                                }
                                item.label(cx, ids!(title)).set_text(cx, &track.title);
                                item.label(cx, ids!(artist)).set_text(cx, track.subtitle());
                                item.label(cx, ids!(duration))
                                    .set_text(cx, &data::seconds(track.duration as f64));
                                item.widget(cx, ids!(favorite))
                                    .set_text(cx, if track.liked { "♥" } else { "♡" });
                                item.widget(cx, ids!(selection))
                                    .set_visible(cx, self.selection.contains(&track.id));
                                set_image(&item, cx, ids!(cover.art), &track.art);
                            } else {
                                item.label(cx, ids!(title))
                                    .set_text(cx, &crate::i18n::tr(&state.language, "Loading…"));
                                item.label(cx, ids!(artist)).set_text(cx, "");
                                need_page = Some(index.saturating_sub(32));
                            }
                            item.draw_all_unscoped(cx);
                        }
                    }
                }
            }
        }
        if let Some(offset) = need_page {
            let request = (state.page.revision, offset / 128 * 128);
            if self.requested != Some(request)
                && client
                    .commands
                    .try_send(data::Command::Page(offset))
                    .is_ok()
            {
                self.requested = Some(request);
            }
        } else {
            self.requested = None;
        }
        if state.auth.kind == "authorizationStateReady"
            && artwork_ids != self.artwork_ids
            && client
                .commands
                .try_send(data::Command::Artwork(artwork_ids.clone()))
                .is_ok()
        {
            self.artwork_ids = artwork_ids;
        }
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        self.view.handle_event(cx, event, scope);
    }
}

pub fn set_image(ui: &WidgetRef, cx: &mut Cx, path: &[LiveId], image: &str) {
    let widget = ui.image(cx, path);
    if image.is_empty() {
        widget.set_visible(cx, false);
    } else {
        widget.set_visible(cx, true);
        let _ = widget.load_image_file_by_path_async(cx, std::path::Path::new(image));
    }
}

#[derive(Script, ScriptHook, Widget)]
pub struct Choices {
    #[deref]
    view: View,
    #[rust]
    pub labels: Vec<String>,
}

impl Widget for Choices {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        while let Some(step) = self.view.draw_walk(cx, scope, walk).step() {
            if let Some(mut list) = step.as_portal_list().borrow_mut() {
                list.set_item_range(cx, 0, self.labels.len());
                while let Some(index) = list.next_visible_item(cx) {
                    if index >= self.labels.len() {
                        continue;
                    }
                    let item = list.item(cx, index, id!(Choice));
                    if let Some(label) = self.labels.get(index) {
                        item.widget(cx, ids!(choice)).set_text(cx, label);
                    }
                    item.draw_all_unscoped(cx);
                }
            }
        }
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        self.view.handle_event(cx, event, scope);
    }
}
