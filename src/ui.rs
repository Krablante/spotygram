use crate::{model as data, runtime};
use makepad_widgets::makepad_platform::event::finger::TouchState;
use makepad_widgets::touch_gesture::{ScrollMode, TouchGesture, TouchMotionChange};
use makepad_widgets::*;
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
};

script_mod! {
    use mod.prelude.widgets.*
    use mod.widgets.*
    use mod.widgets.glass.*

    mod.spotygram.DrawMediaBar = set_type_default() do #(DrawMediaBar::script_shader(vm)){
        ..mod.draw.DrawQuad
        progress:0.0
        inset:6.0
        focus:0.0
        fill:uniform(theme.color_primary)
        ink:uniform(theme.color_text)
        track:uniform(mix(theme.color_bg_app,theme.color_text,0.2))
        pixel:fn(){
            let sdf=Sdf2d.viewport(self.pos*self.rect_size);
            let y=self.rect_size.y*0.5;
            let span=max(self.rect_size.x-self.inset*2.0,1.0);
            let x=self.inset+clamp(self.progress,0.0,1.0)*span;
            sdf.box(self.inset,y-2.0,span,4.0,2.0);sdf.fill(self.track);
            sdf.box(self.inset,y-2.0,max(x-self.inset,0.01),4.0,2.0);sdf.fill(self.fill);
            sdf.circle(x,y,5.0);sdf.fill(self.ink);
            return sdf.result;
        }
    }
    mod.spotygram.Slider = #(MediaSlider::register_widget(vm)){
        width:Fill height:32 min:0.0 max:1.0 step:0.0
        draw_bg:mod.spotygram.DrawMediaBar{}
    }
    mod.spotygram.SpeedSlider = mod.spotygram.Slider {
        height:48
        draw_bg +:{
            inset:14.0
            paper:uniform(theme.color_bg_app)
            track:mix(theme.color_bg_app,theme.color_text,0.10)
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                let y=self.rect_size.y*0.5;
                let span=max(self.rect_size.x-self.inset*2.0,1.0);
                let x=self.inset+clamp(self.progress,0.0,1.0)*span;
                sdf.box(self.inset,y-2.5,span,5.0,1.25);sdf.fill(self.track);
                sdf.box(self.inset,y-2.5,max(x-self.inset,0.01),5.0,1.25);sdf.fill(self.fill);
                sdf.circle(x,y,14.0);sdf.fill(vec4(self.fill.rgb,self.focus*0.15));
                sdf.circle(x,y,12.0);sdf.fill(self.paper);
                sdf.circle(x,y,9.0);sdf.fill(self.fill);
                return sdf.result;
            }
        }
    }

    mod.spotygram.CatalogBase = #(Catalog::register_widget(vm))
    mod.spotygram.ChoicesBase = #(Choices::register_widget(vm))
    mod.spotygram.TouchScroll = #(TouchScroll::register_widget(vm)) {
        scroll_bars:ScrollBars{show_scroll_x:false show_scroll_y:true scroll_bar_y.drag_scrolling:false}
    }
    mod.spotygram.Choices = set_type_default() do mod.spotygram.ChoicesBase {
        width:Fill height:300 flow:Down
        list := PortalList{width:Fill height:Fill scroll_bar:ScrollBar{}
            Quick := View{width:76 height:90 flow:Overlay
                choice := ButtonFlatter{width:Fill height:Fill text:""}
                View{width:Fill height:Fill flow:Down align:Align{x:0.5} spacing:5 padding:Inset{top:4 left:4 right:4}
                    avatar := mod.spotygram.Avatar{width:48 height:48}
                    title := mod.spotygram.Caption{height:32 max_lines:2 align:Center draw_text.text_style.font_size:9}
                }
            }
            Chip := mod.spotygram.Surface{width:Fit height:40 flow:Overlay margin:Inset{right:6} padding:Inset{left:14 right:14 top:10 bottom:8}
                choice := ButtonFlatter{width:Fill height:Fill text:""}
                title := mod.spotygram.Caption{width:Fit height:20}
            }
            Heading := View{width:Fill height:36 padding:Inset{left:12 top:12 bottom:4}
                title := mod.spotygram.Caption{draw_text.color:theme.color_primary}
            }
            Choice := View{width:Fill height:56 flow:Overlay
                surface := mod.spotygram.Surface{visible:false width:Fill height:Fill draw_bg.corner_radius:11.0}
                highlight := RoundedView{width:Fill height:Fill draw_bg +:{border_radius:12.0 color:#x00000000}}
                choice := ButtonFlatter{width:Fill height:Fill text:""}
                View{width:Fill height:Fill flow:Right spacing:12 padding:Inset{left:12 right:12 top:6 bottom:6} align:Align{y:0.5}
                    avatar := mod.spotygram.Avatar{visible:false width:40 height:40}
                    library := View{visible:false width:40 height:40 align:Center
                        mod.spotygram.Mark{width:24 height:24 draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/library.svg")}}
                    }
                    View{width:Fill height:Fit flow:Down spacing:2
                        title := mod.spotygram.Body{height:Fit max_lines:1 text:""}
                        subtitle := mod.spotygram.Caption{visible:false}
                    }
                    checked := View{visible:false width:22 height:22
                        mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/check.svg")}}
                    }
                    radio := View{visible:false width:22 height:22 show_bg:true draw_bg +:{checked:uniform(0.0) ink:uniform(theme.color_text_meta) active:uniform(theme.color_primary)
                        pixel:fn(){let sdf=Sdf2d.viewport(self.pos*self.rect_size);sdf.circle(11.0,11.0,9.0);sdf.stroke(mix(self.ink,self.active,self.checked),1.5);sdf.circle(11.0,11.0,5.0);sdf.fill(vec4(self.active.rgb,self.checked));return sdf.result;}
                    }}
                }
            }
        }
    }
    mod.spotygram.Catalog = set_type_default() do mod.spotygram.CatalogBase {
        width: Fill height: Fill
        list := PortalList {
            width: Fill height: Fill
            auto_tail: false
            scroll_bar: ScrollBar{}
            Song := View {
                width:Fill height:64 flow:Overlay
                highlight := SolidView{width:Fill height:Fill draw_bg.color:#x00000000}
                row_hit := ButtonFlatter{width:Fill height:Fill text:"" enable_long_press:true}
                View{width:Fill height:Fill flow:Right spacing:0 align:Align{y:0.5} padding:Inset{left:16 right:4 top:4 bottom:4}
                selection := View{visible:false width:36 height:48 flow:Overlay align:Align{x:0.0 y:0.5}
                    checkbox := View{width:24 height:24 show_bg:true
                    draw_bg +:{checked:uniform(0.0) ink:uniform(theme.color_text) paper:uniform(theme.color_bg_app) pixel:fn(){
                        let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                        sdf.box(2.0,2.0,20.0,20.0,1.0);
                        sdf.fill_keep(vec4(self.ink.rgb,self.checked));
                        sdf.stroke(vec4(self.ink.rgb,0.75),1.4);
                        if self.checked>0.5 {sdf.move_to(6.0,12.0);sdf.line_to(10.0,16.0);sdf.line_to(18.0,8.0);sdf.stroke(self.paper,2.0);}
                        return sdf.result;
                    }}
                    }
                    select_hit := ButtonFlatter{width:Fill height:Fill text:""}
                }
                cover := mod.spotygram.Cover{play_cover := ButtonFlatter{width:Fill height:Fill text:"" enable_long_press:true}}
                View {
                    width: Fill height: Fill flow: Overlay padding:Inset{left:10 right:4}
                    song := ButtonFlatter {width:Fill height:Fill text:"" enable_long_press:true}
                    View {
                        width: Fill height: Fill flow: Down spacing:0 align:Align{y:0.5}
                        title := mod.spotygram.Body {height:21 draw_text.text_style:theme.font_bold{font_size:12}}
                        View{width:Fill height:18 flow:Right spacing:3 align:Align{y:0.5}
                            saved := View{visible:false width:13 height:13
                                mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/offline.svg")}}
                            }
                            artist := mod.spotygram.Caption{height:18}
                        }
                    }
                }
                duration := mod.spotygram.Caption {width:Fit text:"0:00"}
                favorite := mod.spotygram.PlainIcon {draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/favorite_border.svg")}}
                more := mod.spotygram.PlainIcon {draw_icon.svg:crate_resource("self:resources/more.svg")}
                }
            }
            SourceHeading := View{width:Fill height:40 padding:Inset{left:24 right:16 top:12 bottom:4}
                title := mod.spotygram.Caption{draw_text.color:theme.color_primary}
            }
            QueueEntry := View{width:Fill height:64 flow:Right align:Align{y:0.5}
                View{width:Fill height:Fill flow:Overlay padding:Inset{left:12 right:12}
                    queue_play := ButtonFlatter{width:Fill height:Fill text:""}
                    View{width:Fill height:Fill flow:Down spacing:2 align:Align{y:0.5}
                        title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:12}}
                        artist := mod.spotygram.Caption{}
                    }
                }
                queue_up := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/up.svg")}
                queue_remove := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/close.svg")}
            }
            Source := mod.spotygram.Surface {
                width:Fill height:64 margin:Inset{left:16 right:16} flow:Right spacing:12 align:Align{y:0.5}
                padding:Inset{left:12,right:8,top:10,bottom:10}
                draw_bg.corner_radius:11.0
                cover := mod.spotygram.Avatar{open_cover := ButtonFlatter{width:Fill height:Fill text:""}}
                View {
                    width:Fill height:Fill flow:Overlay
                    open := ButtonFlatter{width:Fill height:Fill text:""}
                    View{width:Fill height:Fill flow:Down spacing:5 align:Align{y:0.5}
                        title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:12}}
                        subtitle := mod.spotygram.Caption{}
                    }
                }
                select := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/more.svg")}
            }
            Playlist := View {
                width:Fill height:76 flow:Right spacing:12 align:Align{y:0.5} padding:Inset{left:20 right:12}
                playlist_cover := RoundedView{width:56 height:56 flow:Overlay align:Center draw_bg +:{border_radius:8.0 color:mix(theme.color_bg_app,theme.color_text,0.08)}
                    mod.spotygram.Mark{width:24 height:24 draw_icon +:{color:theme.color_text svg:crate_resource("self:resources/order.svg")}}
                    single := mod.spotygram.Cover{visible:false width:Fill height:Fill draw_bg.border_radius:16.0 art.draw_bg.border_radius:8.0}
                    mosaic := View{visible:false width:Fill height:Fill flow:Down
                        View{width:Fill height:28 flow:Right
                            art0 := mod.spotygram.Cover{width:28 height:28 draw_bg.border_radius:0.0 note.width:12 note.height:12 art.draw_bg.border_radius:0.0}
                            art1 := mod.spotygram.Cover{width:28 height:28 draw_bg.border_radius:0.0 note.width:12 note.height:12 art.draw_bg.border_radius:0.0}
                        }
                        View{width:Fill height:28 flow:Right
                            art2 := mod.spotygram.Cover{width:28 height:28 draw_bg.border_radius:0.0 note.width:12 note.height:12 art.draw_bg.border_radius:0.0}
                            art3 := mod.spotygram.Cover{width:28 height:28 draw_bg.border_radius:0.0 note.width:12 note.height:12 art.draw_bg.border_radius:0.0}
                        }
                    }
                    View{width:Fill height:Fill show_bg:true draw_bg +:{color:theme.color_bg_app pixel:fn(){
                        let p=self.pos*self.rect_size;
                        let q=abs(p-self.rect_size*0.5)-(self.rect_size*0.5-vec2(16.0));
                        let d=min(max(q.x,q.y),0.0)+length(max(q,vec2(0.0)))-16.0;
                        let alpha=smoothstep(-0.5,0.5,d);
                        return vec4(self.color.rgb*alpha,alpha);
                    }}}
                }
                View{width:Fill height:Fill flow:Overlay
                    open := ButtonFlatter{width:Fill height:Fill text:""}
                    View{width:Fill height:Fill flow:Down spacing:5 align:Align{y:0.5}
                        title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:12}}
                        subtitle := mod.spotygram.Caption{}
                    }
                }
                more := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/more.svg")}
            }
            Empty := View {
                width:Fill height:Fit flow:Down align:Center spacing:12 padding:24
                empty_library := View{width:40 height:40 margin:16
                    mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/library.svg")}}
                }
                empty_favorite := View{visible:false width:40 height:40 margin:16
                    mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/favorite_border.svg")}}
                }
                title := mod.spotygram.Body{height:Fit max_lines:3 align:Center draw_text.text_style.font_size:16.5 text:"Your next favorite song starts here."}
                subtitle := mod.spotygram.Caption{height:Fit max_lines:4 align:Center text:"Import audio files or connect Telegram."}
                empty_connect := mod.spotygram.Flat{text:"Connect Telegram" label_align:Center}
                empty_import := mod.spotygram.Flat{text:"Import audio files" label_align:Center}
                empty_add := mod.spotygram.Glass{visible:false text:"Add recordings"}
                empty_profile := mod.spotygram.Flat{visible:false text:"Open in Telegram" label_align:Center}
            }
            End := View{width:Fill height:150}
        }
    }

    mod.spotygram.RootView = mod.spotygram.Prism {
        flow: Overlay
        main := View {
            width: Fill height: Fill flow: Right
            rail := View {
                width:96 height:Fill flow:Overlay align:Align{x:0.0 y:0.5} padding:Inset{left:16}
                glass.Layer{width:76 height:288
                    mod.spotygram.Surface{width:76 height:288 flow:Overlay padding:Inset{top:8 bottom:8} draw_bg +:{corner_radius:16.0 diffusion:0.32}
                        rail_lens := mod.spotygram.NavLens{draw_bg.vertical:1.0}
                        View{width:Fill height:Fill flow:Down spacing:0
                        rail_music := mod.spotygram.RailNav{text:"Music" draw_icon.svg:crate_resource("self:resources/music.svg")}
                        rail_favorites := mod.spotygram.RailNav{text:"Favorites" draw_icon.svg:crate_resource("self:resources/favorite.svg")}
                        rail_playlists := mod.spotygram.RailNav{text:"Playlists" draw_icon.svg:crate_resource("self:resources/order.svg")}
                        rail_sources := mod.spotygram.RailNav{text:"Sources" draw_icon.svg:crate_resource("self:resources/sources.svg")}
                        }
                    }
                }
            }
            content := View {
                width:Fill height:Fill flow:Overlay align:Align{x:0.5}
                page := View{width:Fill max_width:720 height:Fill flow:Down spacing:0
                header := View{width:Fill height:48 padding:Inset{left:16 right:12} flow:Right spacing:8 align:Align{y:0.5}
                    // All stickers have at least six transparent source pixels
                    // at every edge. Clip that empty margin to prevent Vulkan
                    // sampler-edge streaks without cropping the visible art.
                    header_sticker := Image{width:28 height:28 fit:ImageFit.Smallest draw_bg +:{border_size:1.0 border_color:#x00000000} src:crate_resource("self:resources/emoji_handshake.png")}
                    brand := Label{width:Fill text:"spotygram" draw_text.color:theme.color_text draw_text.text_style:theme.font_bold{font_size:12}}
                    settings := mod.spotygram.Icon{text:"" padding:12 icon_walk:Walk{width:24 height:24} draw_icon +:{color:theme.color_text svg:crate_resource("self:resources/settings.svg")}}
                }
                heading_row := View{width:Fill height:48 padding:Inset{left:16 right:4} flow:Right align:Align{y:0.5} spacing:8
                    back := mod.spotygram.PlainIcon{visible:false draw_icon.svg:crate_resource("self:resources/back.svg")}
                    favorite_heading := View{visible:false width:24 height:24
                        mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text svg:crate_resource("self:resources/favorite.svg")}}
                    }
                    heading := mod.spotygram.Title{text:"Music"}
                    count := mod.spotygram.Caption{width:Fit text:"0 tracks"}
                    add_source_top := mod.spotygram.Glass{visible:false width:Fit text:"Add source" draw_bg +:{accent:1.0 accent_color:mix(#x277347,#x72c987,theme.spotygram_dark)} draw_icon.svg:crate_resource("self:resources/add.svg")}
                    source_refresh_top := mod.spotygram.Icon{visible:false draw_icon.svg:crate_resource("self:resources/refresh.svg")}
                    refresh := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/refresh.svg")}
                    create_playlist := mod.spotygram.Icon{visible:false draw_bg +:{accent:1.0 accent_color:mix(#x785a1a,#xe9d96c,theme.spotygram_dark)} draw_icon.svg:crate_resource("self:resources/add.svg")}
                }
                profile_header := View{visible:false width:Fill height:Fit flow:Right spacing:12 padding:Inset{left:20 right:20 top:10 bottom:10} align:Align{y:0.5}
                    profile_avatar := mod.spotygram.Avatar{width:52 height:52}
                    View{width:Fill height:Fit flow:Down spacing:4
                        profile_username := mod.spotygram.Body{text:"Profile music"}
                        profile_count := mod.spotygram.Caption{text:"Music in Telegram"}
                        profile_connect := mod.spotygram.Glass{width:Fit text:"Add to library" margin:Inset{top:8} icon_walk:Walk{width:18 height:18} draw_icon.svg:crate_resource("self:resources/add.svg")}
                        profile_disconnect := mod.spotygram.Flat{visible:false width:Fit height:40 text:"In library" padding:0 spacing:6 icon_walk:Walk{width:18 height:18} draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/check.svg")} icon_end_walk:Walk{width:18 height:18} draw_icon_end +:{color:theme.color_primary svg:crate_resource("self:resources/down.svg")} draw_text.color:theme.color_primary}
                    }
                }
                search_shell := View{width:Fill height:56 padding:Inset{left:16 right:16 top:4 bottom:4} flow:Overlay
                    mod.spotygram.Surface{width:Fill height:48 flow:Right align:Align{y:0.5} padding:Inset{left:16 right:4}
                        mod.spotygram.Mark{width:20 height:20 draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/search.svg")}}
                        search := mod.spotygram.Input{empty_text:"Search music"}
                    }
                }
                music_controls := View{width:Fill height:Fit flow:Down spacing:0
                    filters := View{width:Fill height:54 padding:Inset{left:16 right:16 top:3 bottom:3} flow:Right spacing:8
                        View{width:Fill height:48 flow:Overlay
                            mod.spotygram.Surface{width:Fill height:Fill
                                source_filter := mod.spotygram.Preference{height:48 row.height:48 row.padding:Inset{left:16 right:16} row.leading.width:20 row.leading.height:20 row.leading.mark.draw_icon.svg:crate_resource("self:resources/library.svg") row.labels.title.draw_text.text_style:theme.font_bold{font_size:10.5} row.labels.detail.visible:false row.arrow.width:18 row.arrow.height:18 row.arrow.mark.draw_icon.svg:crate_resource("self:resources/dropdown.svg")}
                            }
                        }
                        on_device := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/offline.svg")}
                        sort := mod.spotygram.PlainIcon{draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/sort.svg")}}
                    }
                    View{width:Fill height:54 padding:Inset{left:16 right:16 top:3 bottom:3} flow:Right spacing:8
                        play_all := mod.spotygram.Glass{width:Fill text:"Play all" draw_bg.accent:1.0 draw_icon.svg:crate_resource("self:resources/play.svg")}
                        library_order := mod.spotygram.Icon{draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/shuffle.svg")}}
                        select_mode := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/checklist.svg")}
                    }
                }
                collection_controls := View{width:Fill height:64 padding:Inset{left:20 right:20 top:8 bottom:8} flow:Right spacing:8 visible:false
                    add_source := mod.spotygram.Glass{width:Fit text:"Add source" draw_bg +:{accent:1.0 accent_color:mix(#x277347,#x72c987,theme.spotygram_dark)} draw_icon.svg:crate_resource("self:resources/add.svg")}
                    source_refresh := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/refresh.svg")}
                }
                selection_controls := View{width:Fill height:56 padding:Inset{left:16 right:16 top:4 bottom:4} flow:Right spacing:8 visible:false
                    selection_all := mod.spotygram.Glass{width:Fill text:"Select all"}
                    selection_playlist := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/playlist_add.svg")}
                    selection_download := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/download.svg")}
                }
                // The pinned Label applies its walk margin around both turtles.
                notice := mod.spotygram.Caption{visible:false height:Fit max_lines:3 margin:Inset{left:8 right:8 top:2 bottom:2}}
                catalog := mod.spotygram.Catalog{}
                }
                dock := View{width:Fill max_width:720 height:Fill flow:Down padding:Inset{left:12 right:12 bottom:6} spacing:6
                Filler{}
                player_dock := View{width:Fill height:56 flow:Overlay visible:false
                    glass.Layer{width:Fill height:Fill
                        mod.spotygram.CapturedSurface{width:Fill height:Fill flow:Right spacing:4 padding:Inset{left:7 right:4 top:4 bottom:4} align:Align{y:0.5} draw_bg +:{corner_radius:14.0 diffusion:0.32 environment:0.0 blur_level:4.5}
                            mini_cover := mod.spotygram.Cover{width:44 height:44}
                            View{width:Fill height:Fill flow:Overlay
                                show_player := ButtonFlatter{width:Fill height:Fill text:""}
                                View{width:Fill height:Fill flow:Down spacing:0 padding:Inset{left:6 right:4} align:Align{y:0.5}
                                    mini_title := mod.spotygram.Body{height:22 draw_text.text_style:theme.font_bold{font_size:12}}
                                    View{width:Fill height:18 flow:Right spacing:5
                                        mini_artist := mod.spotygram.Caption{width:Fit}
                                        mini_speed := mod.spotygram.Caption{width:Fit visible:false draw_text.color:theme.color_primary}
                                    }
                                }
                            }
                            previous := mod.spotygram.PlainIcon{width:40 draw_icon.svg:crate_resource("self:resources/previous.svg")}
                            mini_play := mod.spotygram.InlineIcon{draw_icon.svg:crate_resource("self:resources/play.svg")}
                            next := mod.spotygram.PlainIcon{width:40 draw_icon.svg:crate_resource("self:resources/next.svg")}
                        }
                    }
                }
                bottom_nav := View{width:Fill height:62 flow:Overlay
                    glass.Layer{width:Fill height:Fill
                        mod.spotygram.CapturedSurface{width:Fill height:Fill flow:Overlay padding:Inset{left:5 right:5 top:5 bottom:5} draw_bg +:{corner_radius:15.5 diffusion:0.32 environment:0.0 blur_level:4.5}
                            nav_lens := mod.spotygram.NavLens{}
                            View{width:Fill height:Fill flow:Right spacing:0
                            nav_music := mod.spotygram.Nav{text:"Music" draw_icon.svg:crate_resource("self:resources/music.svg")}
                            nav_favorites := mod.spotygram.Nav{text:"Favorites" draw_icon.svg:crate_resource("self:resources/favorite.svg")}
                            nav_playlists := mod.spotygram.Nav{text:"Playlists" draw_icon.svg:crate_resource("self:resources/order.svg")}
                            nav_sources := mod.spotygram.Nav{text:"Sources" draw_icon.svg:crate_resource("self:resources/sources.svg")}
                            }
                        }
                    }
                }
                }
            }
        }
        full_player := mod.spotygram.Prism{visible:false flow:Overlay padding:Inset{left:20 right:20 top:0 bottom:12} align:Align{x:0.5}
            View{width:Fill height:Fill flow:Down align:Align{x:0.5}
                Filler{}
                player_reflection := mod.spotygram.Reflection{}
            }
            View{width:Fill max_width:1000 height:Fill flow:Down spacing:16
            View{width:Fill height:56 flow:Right align:Align{y:0.5}
                player_close := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/down.svg")}
                View{width:Fill height:Fit flow:Down align:Center spacing:4
                    playing_label := Label{text:"PLAYING FROM" align:Center draw_text.color:theme.color_text_meta draw_text.text_style.font_size:7.5}
                    playing_source := mod.spotygram.Caption{align:Center text:"From device"}
                }
                player_share := mod.spotygram.Icon{visible:false margin:Inset{right:8} draw_icon.svg:crate_resource("self:resources/share.svg")}
                player_more := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/more.svg")}
            }
            player_columns := mod.spotygram.TouchScroll{constrain_touch:true width:Fill height:Fill flow:Right spacing:24 align:Center
                player_art := mod.spotygram.Cover{width:320 height:320
                    draw_bg.border_radius:24.0
                    note +:{width:100 height:100}
                    art.draw_bg.border_radius:12.0
                }
                player_details := View{width:Fill max_width:520 height:Fit flow:Down spacing:0
                    View{width:Fill height:Fit flow:Right spacing:10 align:Align{y:0.5}
                        View{width:Fill height:Fit flow:Down spacing:6
                            player_title := mod.spotygram.Title{height:Fit max_lines:2 draw_text.text_style.font_size:21 text:""}
                            player_artist := mod.spotygram.Body{draw_text.color:theme.color_text_meta text:""}
                        }
                        player_favorite := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/favorite_border.svg")}
                    }
                    player_notice := mod.spotygram.Caption{visible:false height:Fit max_lines:3 margin:Inset{top:8}}
                    View{width:Fill height:16}
                    seek := mod.spotygram.Slider{min:0.0 max:1.0 step:0.0}
                    View{width:Fill height:20 flow:Right
                        elapsed := mod.spotygram.Caption{width:Fill text:"0:00"}
                        duration := mod.spotygram.Caption{width:Fit text:"0:00"}
                    }
                    View{width:Fill height:100 padding:Inset{top:12 bottom:12} flow:Overlay
                        glass.Layer{width:Fill height:Fill
                            mod.spotygram.Surface{width:Fill height:76 flow:Right padding:Inset{left:4 right:4 top:6 bottom:6} spacing:0 align:Align{y:0.5} draw_bg.corner_radius:19.0
                                order := mod.spotygram.PlainIcon{width:Fill padding:12 draw_icon.svg:crate_resource("self:resources/order.svg")}
                                player_previous := mod.spotygram.PlainIcon{width:Fill padding:8 icon_walk:Walk{width:32 height:32} draw_icon.svg:crate_resource("self:resources/previous.svg")}
                                player_play := mod.spotygram.InlineIcon{width:64 height:64 padding:11 icon_walk:Walk{width:42 height:42} draw_icon.svg:crate_resource("self:resources/play.svg") draw_bg +:{border_radius:32.0 color:mix(theme.color_bg_app,theme.color_primary,0.16)}}
                                player_next := mod.spotygram.PlainIcon{width:Fill padding:8 icon_walk:Walk{width:32 height:32} draw_icon.svg:crate_resource("self:resources/next.svg")}
                                repeat := mod.spotygram.PlainIcon{width:Fill padding:12 draw_icon.svg:crate_resource("self:resources/repeat.svg")}
                            }
                        }
                    }
                    View{width:Fill height:56 padding:Inset{top:4 bottom:4} flow:Right spacing:8
                        player_save := mod.spotygram.Glass{width:Fill padding:8 spacing:5 text:"Save on device" icon_walk:Walk{width:20 height:20} draw_icon.svg:crate_resource("self:resources/download.svg")}
                        speed := mod.spotygram.Glass{width:48 padding:0 text:"1×" draw_bg.accent_color:mix(#x086d79,#x58c9d2,theme.spotygram_dark)}
                        queue := mod.spotygram.Glass{width:Fill padding:8 spacing:5 text:"Queue" icon_walk:Walk{width:20 height:20} draw_icon.svg:crate_resource("self:resources/queue.svg")}
                    }
                    volume_row := View{width:Fill height:44 flow:Right align:Align{y:0.5} spacing:8
                        mute := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/volume.svg")}
                        volume := mod.spotygram.Slider{min:0.0 max:100.0 step:1.0}
                        volume_label := mod.spotygram.Caption{width:56 text:"100%"}
                    }
                }
            }
            }
        }
        playlist_editor := mod.spotygram.Prism{visible:false flow:Overlay align:Align{x:0.5}
            View{width:Fill max_width:720 height:Fill flow:Down
                View{width:Fill height:56 flow:Right spacing:12 padding:Inset{left:4 right:16} align:Align{y:0.5}
                    editor_close := mod.spotygram.Icon{draw_icon.svg:crate_resource("self:resources/back.svg")}
                    editor_title := mod.spotygram.Title{text:"New playlist"}
                }
                editor_playlist_name := mod.spotygram.Caption{visible:false height:28 margin:Inset{left:8 right:8}}
                editor_fields := View{width:Fill height:Fit flow:Down
                    editor_name_shell := View{width:Fill height:60 padding:Inset{left:16 right:16 top:6 bottom:6}
                        mod.spotygram.Surface{width:Fill height:48 editor_name := mod.spotygram.Input{empty_text:"Playlist name"}}
                    }
                    View{width:Fill height:60 padding:Inset{left:16 right:16 top:6 bottom:6}
                        mod.spotygram.Surface{width:Fill height:48 flow:Right align:Align{y:0.5} padding:Inset{left:16 right:4}
                            mod.spotygram.Mark{width:20 height:20 draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/search.svg")}}
                            editor_search := mod.spotygram.Input{empty_text:"Title or artist"}
                        }
                    }
                }
                View{width:Fill height:48 flow:Right padding:Inset{left:16 right:8} align:Align{y:0.5}
                    editor_count := mod.spotygram.Body{text:"0 selected"}
                    editor_select_all := mod.spotygram.Flat{width:Fit height:48 text:"Select all" draw_text.color:theme.color_primary}
                }
                editor_error := mod.spotygram.Caption{visible:false height:Fit max_lines:3 margin:8 draw_text.color:mix(#xb84750,#xffa2a2,theme.spotygram_dark)}
                editor_catalog := mod.spotygram.Catalog{reserve_dock:false}
                editor_save := mod.spotygram.Glass{width:Fill height:48 margin:16 text:"Create playlist" draw_bg +:{accent:1.0 accent_color:mix(#x785a1a,#xe9d96c,theme.spotygram_dark)}}
            }
        }
        settings_page := SolidView{visible:false new_batch:true width:Fill height:Fill flow:Down align:Align{x:0.5} draw_bg.color:theme.color_bg_app
            settings_shell := View{width:Fill max_width:960 height:Fill padding:Inset{left:16 right:16 top:8 bottom:16} flow:Down spacing:16
                settings_header := View{width:Fill height:48 flow:Right spacing:8 align:Align{y:0.5}
                    settings_done := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/back.svg")}
                    settings_title := mod.spotygram.Title{height:Fill align:Align{x:0.0 y:0.5} text:"Settings" draw_text.text_style.font_size:18}
                }
                settings_error := mod.spotygram.Body{visible:false height:Fit max_lines:3 draw_text.color:mix(#xb84750,#xffa2a2,theme.spotygram_dark)}
                settings_layout := View{width:Fill height:Fill flow:Right spacing:24
                    settings_nav := View{width:176 height:Fit flow:Down spacing:4
                        settings_general_tab := mod.spotygram.SettingsChoice{text:"General"}
                        settings_player_tab := mod.spotygram.SettingsChoice{text:"Player"}
                        settings_storage_tab := mod.spotygram.SettingsChoice{text:"Storage"}
                        settings_telegram_tab := mod.spotygram.SettingsChoice{text:"Telegram"}
                    }
                    View{width:Fill height:Fill flow:Overlay
                        settings_general_page := mod.spotygram.TouchScroll{width:Fill height:Fill constrain_touch:true flow:Down spacing:12 padding:Inset{right:4 bottom:8}
                            mod.spotygram.SettingsCard{
                                settings_appearance := mod.spotygram.Section{text:"Appearance"}
                                settings_theme_choices := View{width:Fill height:48 flow:Right spacing:4
                                    theme_light := mod.spotygram.SettingsChoice{text:"Light"}
                                    theme_dark := mod.spotygram.SettingsChoice{text:"Dark"}
                                }
                                settings_language_label := mod.spotygram.Section{text:"Language" margin:Inset{top:8}}
                                language_choices := View{width:Fill height:48 flow:Right spacing:4
                                    language_system := mod.spotygram.SettingsChoice{text:"System" padding:2}
                                    language_en := mod.spotygram.SettingsChoice{text:"English" padding:2}
                                    language_ru := mod.spotygram.SettingsChoice{text:"Русский" padding:2}
                                }
                            }
                            mod.spotygram.SettingsCard{
                                settings_library_label := mod.spotygram.Section{text:"Library"}
                                settings_duplicates := mod.spotygram.SettingsToggle{row.labels.title.text:"Hide duplicates" row.labels.detail.text:"One entry per recording. Files stay on this device."}
                            }
                            mod.spotygram.SettingsCard{
                                settings_application_label := mod.spotygram.Section{text:"Application"}
                                settings_about := mod.spotygram.Caption{height:Fit max_lines:2}
                                settings_app_actions := View{width:Fill height:Fit flow:Right spacing:8
                                    settings_update := mod.spotygram.SettingsAction{width:220 row.labels.title.text:"Check for updates"}
                                    settings_quit := mod.spotygram.SettingsAction{width:80 row.labels.title.text:"Quit"}
                                }
                                settings_update_result := mod.spotygram.Caption{visible:false height:Fit max_lines:3}
                            }
                        }
                        settings_player_page := mod.spotygram.TouchScroll{visible:false width:Fill height:Fill constrain_touch:true flow:Down spacing:12 padding:Inset{right:4 bottom:8}
                            mod.spotygram.SettingsCard{
                                settings_playback_label := mod.spotygram.Section{text:"Playback"}
                                settings_bookmarks := mod.spotygram.SettingsToggle{row.labels.title.text:"Remember audio positions" row.labels.detail.text:"Continue long recordings where you stopped."}
                                settings_minutes := mod.spotygram.SettingsRow{row.labels.title.text:"Audio duration threshold"}
                            }
                            mod.spotygram.SettingsCard{
                                settings_sharing_label := mod.spotygram.Section{text:"Sharing audio"}
                                settings_caption := mod.spotygram.SettingsToggle{row.labels.title.text:"Keep original caption"}
                                settings_sender := mod.spotygram.SettingsToggle{row.labels.title.text:"Keep original sender" row.labels.detail.text:"Also keeps the original caption."}
                            }
                        }
                        settings_storage_page := mod.spotygram.TouchScroll{visible:false width:Fill height:Fill constrain_touch:true flow:Down spacing:12 padding:Inset{right:4 bottom:8}
                            mod.spotygram.SettingsCard{
                                settings_saved_label := mod.spotygram.Section{text:"Saved music"}
                                settings_storage := mod.spotygram.SettingsRow{row.labels.title.text:"On device"}
                                settings_import := mod.spotygram.SettingsAction{row.labels.title.text:"Import audio files" row.leading.visible:true row.leading.mark.draw_icon.svg:crate_resource("self:resources/add.svg")}
                            }
                            mod.spotygram.SettingsCard{
                                settings_downloads_label := mod.spotygram.Section{text:"Downloads"}
                                settings_wifi := mod.spotygram.SettingsToggle{row.labels.title.text:"Downloads over Wi-Fi only" row.labels.detail.text:"Streaming can use mobile data."}
                                settings_discard := mod.spotygram.SettingsToggle{row.labels.title.text:"Do not keep played tracks" row.labels.detail.text:"Saved downloads and imports stay."}
                                settings_transfer_row := View{width:Fill height:48 flow:Right spacing:8 align:Align{y:0.5}
                                    settings_transfers_status := mod.spotygram.Caption{height:Fit max_lines:3}
                                    settings_transfers := mod.spotygram.Glass{width:Fit padding:12 text:"Cancel"}
                                }
                            }
                            mod.spotygram.SettingsCard{
                                settings_cache_label := mod.spotygram.Section{text:"Music cache"}
                                settings_cache_stats := View{width:Fill height:48 flow:Right spacing:8 align:Align{y:0.5}
                                    View{width:Fill height:Fit flow:Down spacing:4
                                        cache_size := mod.spotygram.Body{height:Fit text:"—" draw_text.text_style:theme.font_bold{font_size:15}}
                                        cache_count := mod.spotygram.Caption{height:Fit max_lines:2}
                                    }
                                    cache_loading := SpinnerFlat{visible:false size:20 text:""}
                                    cache_refresh := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/refresh.svg")}
                                }
                                settings_cache_help := mod.spotygram.Caption{height:Fit max_lines:3 text:"Telegram audio. Imported files stay."}
                                cache_clear := mod.spotygram.SettingsAction{row.labels.title.text:"Clear music cache" row.leading.visible:true row.leading.mark.draw_icon.svg:crate_resource("self:resources/delete.svg")}
                                settings_cache_connect := mod.spotygram.SettingsAction{visible:false row.labels.title.text:"Connect Telegram" row.leading.visible:true row.leading.mark.draw_icon.svg:crate_resource("self:resources/telegram.svg")}
                            }
                        }
                        settings_telegram_page := mod.spotygram.TouchScroll{visible:false width:Fill height:Fill constrain_touch:true flow:Down spacing:12 padding:Inset{right:4 bottom:8}
                            mod.spotygram.SettingsCard{
                                settings_account_label := mod.spotygram.Section{text:"Account"}
                                settings_connection := mod.spotygram.Body{height:Fit max_lines:2}
                                settings_account := mod.spotygram.SettingsAction{row.labels.title.text:"Connect Telegram" row.leading.visible:true row.leading.mark.draw_icon.svg:crate_resource("self:resources/telegram.svg")}
                            }
                            mod.spotygram.SettingsCard{
                                settings_sync_label := mod.spotygram.Section{text:"Library sync"}
                                settings_sync := mod.spotygram.Body{height:Fit max_lines:5}
                                settings_sync_refresh := mod.spotygram.SettingsAction{row.labels.title.text:"Refresh library" row.leading.visible:true row.leading.mark.draw_icon.svg:crate_resource("self:resources/refresh.svg")}
                            }
                        }
                    }
                }
            }
        }
        overlay := View{visible:false width:Fill height:Fill flow:Overlay align:Center
            auth_background := mod.spotygram.Prism{visible:false}
            scrim := SolidView{width:Fill height:Fill draw_bg.color:#x00000022}
            panel_layer := glass.Layer{width:Fill height:Fill align:Center
                panel_position := View{width:Fill height:Fill flow:Overlay align:Center
                panel := mod.spotygram.CapturedSurface{width:500 height:Fit flow:Down spacing:10 padding:20
                    draw_bg +:{blur_level:4.5 diffusion:mix(0.32,0.38,theme.spotygram_dark) corner_radius:14.0 environment:0.0}
                    drag_handle := View{visible:false width:Fill height:16 align:Center
                        RoundedView{width:32 height:4 draw_bg +:{color:theme.color_text_meta border_radius:2.0}}
                    }
                    panel_header := View{width:Fill height:48 flow:Right align:Align{y:0.5} spacing:10
                        auth_back := mod.spotygram.Icon{visible:false draw_icon.svg:crate_resource("self:resources/back.svg")}
                        panel_back := mod.spotygram.PlainIcon{visible:false draw_icon.svg:crate_resource("self:resources/back.svg")}
                    panel_title := mod.spotygram.Title{height:Fill align:Align{x:0.0 y:0.5} max_lines:2 draw_text.text_style.font_size:15 text:"Settings"}
                        panel_close := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/close.svg")}
                    }
                    panel_error := mod.spotygram.Body{visible:false height:Fit max_lines:3 draw_text.color:mix(#xb84750,#xffa2a2,theme.spotygram_dark)}
                    panel_scroll := mod.spotygram.TouchScroll{width:Fill height:Fit flow:Down spacing:12
                        auth_mark := View{visible:false width:Fill height:132 flow:Down spacing:12 align:Center
                            auth_photo := Image{width:64 height:64 fit:ImageFit.Smallest draw_bg +:{border_size:1.0 border_color:#x00000000} src:crate_resource("self:resources/spotygram_mark.png")}
                            auth_name := Label{text:"spotygram" draw_text.color:theme.color_text draw_text.text_style:theme.font_bold{font_size:21}}
                        }
                        auth_heading := mod.spotygram.Body{visible:false height:Fit max_lines:2 draw_text.text_style:theme.font_bold{font_size:16.5} text:"Connect Telegram"}
                        panel_message := mod.spotygram.Body{height:Fit max_lines:8 visible:false}
                        send_header := View{visible:false width:Fill height:56 flow:Right spacing:12 align:Align{y:0.5}
                            send_cover := mod.spotygram.Cover{}
                            View{width:Fill height:Fit flow:Down spacing:2
                                send_title := mod.spotygram.Body{draw_text.text_style:theme.font_bold{font_size:12}}
                                send_artist := mod.spotygram.Caption{}
                            }
                        }
                        directory_folder := mod.spotygram.Flat{visible:false text:"All chats"}
                        panel_input_wrap := mod.spotygram.Surface{visible:false width:Fill height:48 flow:Right align:Align{y:0.5}
                            panel_search_icon := View{visible:false width:20 height:20
                                mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/search.svg")}}
                            }
                            panel_input := mod.spotygram.Input{}
                        }
                        auth_qr_area := View{visible:false width:Fill height:240 align:Center
                            auth_qrcode := Image{width:240 height:240 fit:ImageFit.Smallest}
                        }
                        quick_body := View{visible:false width:Fill height:Fit flow:Down spacing:4
                            quick_label := mod.spotygram.Section{text:"Quick access"}
                            quick_choices := mod.spotygram.Choices{height:90 layout_mode:2 list.flow:Right}
                        }
                        folder_choices := mod.spotygram.Choices{visible:false height:42 layout_mode:1 list.flow:Right}
                        auth_actions := View{visible:false width:Fill height:Fit flow:Down spacing:8
                            auth_submit := mod.spotygram.InlineGlass{width:Fill text:"Continue"}
                            auth_qr := mod.spotygram.Flat{text:"Sign in with QR code"}
                            auth_phone := mod.spotygram.Flat{text:"Use another phone number"}
                            auth_resend := mod.spotygram.Flat{text:"Resend code"}
                        }
                        sharing_body := View{visible:false width:Fill height:Fit flow:Down spacing:12
                            sharing_help := mod.spotygram.Body{height:Fit max_lines:4 text:"Sender and original caption"}
                            settings_sharing_sender := mod.spotygram.TogglePreference{row.labels.title.text:"Keep original sender"}
                            settings_sharing_caption := mod.spotygram.TogglePreference{row.labels.title.text:"Keep original caption"}
                        }
                        track_body := View{visible:false width:Fill height:Fit flow:Down spacing:0
                            track_header := View{width:Fill height:76 flow:Right spacing:16 align:Align{y:0.5}
                                track_cover := mod.spotygram.Cover{width:48 height:48}
                                View{width:Fill height:Fit flow:Down spacing:6
                                    track_title := mod.spotygram.Body{height:Fit max_lines:2}
                                    track_artist := mod.spotygram.Caption{}
                                }
                            }
                            profile_member_wrap := View{width:Fill height:60 align:Center
                                track_profile_member := mod.spotygram.InlineGlass{width:128 text:"To profile" icon_walk:Walk{width:18 height:18} draw_text.text_style.font_size:9 draw_icon.svg:crate_resource("self:resources/person_add.svg")}
                            }
                            track_profile_hint := mod.spotygram.Caption{visible:false height:Fit max_lines:3 text:"This local recording will be uploaded to your Telegram profile."}
                            track_send := mod.spotygram.Flat{text:"Send to Telegram chat" draw_icon.svg:crate_resource("self:resources/share.svg")}
                            track_favorite := mod.spotygram.Flat{text:"Add to favorites" draw_icon.svg:crate_resource("self:resources/favorite_border.svg")}
                            track_playlist := mod.spotygram.Flat{text:"Add to playlist" draw_icon.svg:crate_resource("self:resources/playlist_add.svg")}
                            track_queue := mod.spotygram.Flat{text:"Add to queue" draw_icon.svg:crate_resource("self:resources/order.svg")}
                            track_order := mod.spotygram.Preference{row.leading.mark.draw_icon.svg:crate_resource("self:resources/order.svg") row.labels.title.text:"Playback order" row.arrow.visible:false}
                            track_move_up := mod.spotygram.Flat{text:"Move up" draw_icon.svg:crate_resource("self:resources/order.svg")}
                            track_move_down := mod.spotygram.Flat{text:"Move down" draw_icon.svg:crate_resource("self:resources/down.svg")}
                            track_remove_queue := mod.spotygram.Flat{text:"Remove from queue" draw_icon.svg:crate_resource("self:resources/close.svg")}
                            track_download := mod.spotygram.Flat{text:"Save on device" draw_icon.svg:crate_resource("self:resources/download.svg")}
                            track_profile := mod.spotygram.Flat{text:"Open Telegram profile" draw_icon.svg:crate_resource("self:resources/person.svg")}
                            track_original := mod.spotygram.Flat{text:"Open original message" draw_icon.svg:crate_resource("self:resources/telegram.svg")}
                            track_remove_playlist := mod.spotygram.Flat{text:"Remove from this playlist" draw_icon.svg:crate_resource("self:resources/close.svg")}
                            track_delete := mod.spotygram.Flat{text:"Remove local copy" draw_icon.svg:crate_resource("self:resources/delete.svg")}
                        }
                        choices_body := mod.spotygram.Choices{visible:false}
                        queue_summary := mod.spotygram.Caption{visible:false height:Fit max_lines:3}
                        queue_catalog := mod.spotygram.Catalog{visible:false height:360 reserve_dock:false}
                        speed_body := View{visible:false width:Fill height:Fit flow:Down spacing:4 padding:Inset{bottom:4}
                            View{width:Fill height:60 align:Center
                                View{width:Fit height:48 flow:Right spacing:24 align:Align{y:0.5}
                                    speed_minus := mod.spotygram.SpeedStep{draw_icon.svg:crate_resource("self:resources/remove.svg")}
                                    speed_value := mod.spotygram.Title{width:88 height:Fit text:"1×" align:Center draw_text.text_style.font_size:19.5}
                                    speed_plus := mod.spotygram.SpeedStep{draw_icon.svg:crate_resource("self:resources/add.svg")}
                                }
                            }
                            speed_slider := mod.spotygram.SpeedSlider{min:#(data::MIN_PLAYBACK_SPEED) max:#(data::MAX_PLAYBACK_SPEED) step:#(data::PLAYBACK_SPEED_STEP)}
                            View{width:Fill height:48 flow:Right spacing:6
                                speed_slow := mod.spotygram.SpeedPreset{text:"0.5×"}
                                speed_normal := mod.spotygram.SpeedPreset{text:"1×"}
                                speed_fast := mod.spotygram.SpeedPreset{text:"1.5×"}
                                speed_double := mod.spotygram.SpeedPreset{text:"2×"}
                                speed_triple := mod.spotygram.SpeedPreset{text:"3×"}
                            }
                        }
                    }
                    manage_sources := mod.spotygram.Flat{visible:false text:"Manage sources" draw_icon.svg:crate_resource("self:resources/sources.svg")}
                    send_options := View{visible:false width:Fill height:48 flow:Right align:Align{y:0.5}
                        send_summary := mod.spotygram.Caption{width:Fill}
                        send_pin := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/pin.svg")}
                        send_settings := mod.spotygram.PlainIcon{draw_icon.svg:crate_resource("self:resources/settings.svg")}
                    }
                    panel_footer := View{visible:false width:Fill height:Fit flow:Right spacing:8
                        panel_cancel := mod.spotygram.Flat{visible:false width:Fill text:"Cancel"}
                        panel_confirm := mod.spotygram.InlineGlass{visible:false width:Fill text:"Confirm"}
                    }
                }
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
    #[live(6.0)]
    inset: f32,
    #[live]
    focus: f32,
}

/// A music slider: a tap sets an absolute value and never opens a numeric keyboard.
/// Keep native wheel/scrollbar behavior and let a touch drag start over a row
/// button. The pinned ScrollYView only joins presses held by other scrollers.
#[derive(Script, ScriptHook, Widget)]
pub struct TouchScroll {
    #[deref]
    view: View,
    #[live]
    constrain_touch: bool,
    #[rust]
    touch: Option<TouchGesture>,
    #[rust]
    press: Option<(u64, Vec2d)>,
    #[rust]
    claimed: bool,
}

impl TouchScroll {
    fn touch_event(&mut self, cx: &mut Cx, event: &Event, start: bool) {
        let Some(extent) = self.view.scroll_extent() else {
            return;
        };
        if self.constrain_touch && extent.max().y <= 0.0 {
            self.touch = None;
            return;
        }
        if start {
            let mut touch = TouchGesture::new();
            touch.set_mode(ScrollMode::Swipe);
            touch.scrolled_at = extent.pos.y;
            self.touch = Some(touch);
        }
        if let Some(touch) = &mut self.touch {
            touch.set_range(0., extent.max().y);
            if touch.handle_event(cx, event, self.view.area())
                == TouchMotionChange::ScrolledAtChanged
            {
                let position = touch.scrolled_at.clamp(0., extent.max().y);
                self.view.set_scroll_pos(cx, dvec2(extent.pos.x, position));
                if self.constrain_touch {
                    // The visible viewport is clamped: keep the gesture in the
                    // same range so reversing at an edge has no hidden dead zone.
                    touch.scrolled_at = position;
                    if !touch.is_dragging() && (position <= 0.0 || position >= extent.max().y) {
                        self.touch = None;
                    }
                }
            }
        }
    }
}

impl Widget for TouchScroll {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        self.view.draw_walk(cx, scope, walk)
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        if !self.view.visible() || matches!(event, Event::MouseDown(_) | Event::Scroll(_)) {
            self.touch = None;
        }
        let press = match event {
            Event::TouchUpdate(e) => e
                .touches
                .iter()
                .find(|t| {
                    t.state == TouchState::Start
                        && self.view.area().clipped_rect(cx).contains(t.abs)
                })
                .map(|t| (t.uid, t.abs)),
            _ => None,
        };
        let start = press.is_some();
        if start {
            self.press = press;
            self.claimed = false;
        }
        // A vertical swipe cancels the row's press after touch slop. Otherwise
        // a moved button can still be under the finger when it is released.
        if !self.claimed && self.touch.as_ref().is_some_and(TouchGesture::is_dragging) {
            if let (Some((uid, origin)), Event::TouchUpdate(e)) = (self.press, event) {
                let digit = live_id_num!(touch, uid).into();
                if !cx.fingers.is_drag_claimed_by_widget(digit)
                    && e.touches
                        .iter()
                        .any(|t| t.uid == uid && t.abs.distance(&origin) > 8.0)
                {
                    cx.fingers.cancel_digit_except(digit, self.view.area());
                    cx.set_key_focus(Area::Empty);
                    self.claimed = true;
                }
            }
        }
        // Catching momentum consumes this press before a child can activate.
        let caught = start
            && self
                .touch
                .as_ref()
                .is_some_and(|t| !t.is_stopped() && !t.is_dragging());
        if caught {
            self.touch_event(cx, event, true);
        }
        self.view.handle_event(cx, event, scope);
        if !self.view.visible() {
            return;
        }
        if matches!(event, Event::TouchUpdate(e) if e.touches.iter().any(|t|
            cx.fingers.is_drag_claimed_by_widget(live_id_num!(touch, t.uid).into())))
        {
            self.touch = None;
            return;
        }
        if !caught
            && matches!(
                event,
                Event::TouchUpdate(_) | Event::NextFrame(_) | Event::FingerCancel(_)
            )
        {
            self.touch_event(cx, event, start);
        }
    }
}

impl TouchScrollRef {
    pub fn stop_motion(&self) {
        if let Some(mut scroll) = self.borrow_mut() {
            scroll.touch = None;
            scroll.press = None;
        }
    }
    pub fn set_scroll_pos(&self, cx: &mut Cx, value: Vec2d) {
        if let Some(mut scroll) = self.borrow_mut() {
            scroll.touch = None;
            scroll.press = None;
            scroll.view.set_scroll_pos(cx, value);
        }
    }
}

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
        let inset = self.draw_bg.inset as f64;
        let fraction = ((position.x - rect.pos.x - inset) / (rect.size.x - inset * 2.0).max(1.0))
            .clamp(0.0, 1.0);
        self.assign(cx, self.min + (self.max - self.min) * fraction);
    }
}

impl Widget for MediaSlider {
    fn draw_walk(&mut self, cx: &mut Cx2d, _: &mut Scope, walk: Walk) -> DrawStep {
        self.draw_bg.progress = ((self.value - self.min) / (self.max - self.min).max(f64::EPSILON))
            .clamp(0.0, 1.0) as f32;
        self.draw_bg.draw_walk(cx, walk);
        cx.add_nav_stop(self.draw_bg.area(), NavRole::Slider, Inset::default());
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, _: &mut Scope) {
        match event.hits(cx, self.draw_bg.area()) {
            Hit::KeyFocus(_) => {
                self.draw_bg.focus = 1.0;
                self.draw_bg.redraw(cx);
            }
            Hit::KeyFocusLost(_) => {
                self.draw_bg.focus = 0.0;
                self.draw_bg.redraw(cx);
            }
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

#[derive(Clone, Copy)]
pub enum SourceEntry {
    Own,
    Heading(&'static str),
    Source {
        index: usize,
        position: usize,
        length: usize,
    },
}

pub fn source_rows(state: &data::Snapshot) -> Vec<SourceEntry> {
    if state.auth.kind != "authorizationStateReady" && !state.sources.iter().any(|s| s.selected) {
        return Vec::new();
    }
    let search = state.page.query.search.to_lowercase();
    let mut rows = if search.is_empty()
        || crate::i18n::tr(&state.language, "My profile")
            .to_lowercase()
            .contains(&search)
    {
        vec![SourceEntry::Own]
    } else {
        Vec::new()
    };
    for (profile, title) in [(true, "Profiles"), (false, "Chats")] {
        let indices: Vec<_> = state
            .sources
            .iter()
            .enumerate()
            .filter(|(_, s)| s.selected && s.profile == profile && !(profile && s.id == state.me))
            .filter(|(_, s)| s.title.to_lowercase().contains(&search))
            .map(|(i, _)| i)
            .collect();
        if indices.is_empty() {
            continue;
        }
        rows.push(SourceEntry::Heading(title));
        for (position, index) in indices.iter().enumerate() {
            rows.push(SourceEntry::Source {
                index: *index,
                position,
                length: indices.len(),
            });
        }
    }
    rows
}

pub fn playlist_indices(state: &data::Snapshot) -> Vec<usize> {
    let search = state.page.query.search.to_lowercase();
    state
        .playlists
        .iter()
        .enumerate()
        .filter(|(_, p)| p.name.to_lowercase().contains(&search))
        .map(|(i, _)| i)
        .collect()
}

#[derive(Script, ScriptHook, Widget)]
pub struct Catalog {
    #[deref]
    view: View,
    #[live(true)]
    reserve_dock: bool,
    #[rust]
    requested: Option<(u64, usize)>,
    #[rust]
    pub selection: HashSet<String>,
    #[rust]
    pub selecting: bool,
    #[rust]
    pub selection_version: u64,
    #[rust]
    artwork_ids: Vec<String>,
    #[rust]
    row_icons: HashMap<WidgetUid, (String, bool, bool, u8)>,
    #[rust]
    playlist_art: HashMap<WidgetUid, Vec<data::Artwork>>,
    #[rust]
    source_layout: HashMap<WidgetUid, (usize, u64)>,
    #[rust]
    source_photo_ids: Vec<(i64, bool)>,
    #[rust]
    source_snapshot: Option<std::sync::Arc<Vec<data::Source>>>,
    #[rust]
    source_rows: Vec<SourceEntry>,
    #[rust]
    source_account: i64,
    #[rust]
    source_ready: bool,
    #[rust]
    source_generation: u64,
    #[rust]
    source_query: String,
}

impl Widget for Catalog {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        let client = runtime::start();
        let state = client.snapshot.load_full();
        let screen = &state.page.query.screen;
        if *screen == data::Screen::Sources {
            let ready = state.auth.kind == "authorizationStateReady";
            if self
                .source_snapshot
                .as_ref()
                .is_none_or(|old| !std::sync::Arc::ptr_eq(old, &state.sources))
                || self.source_account != state.me
                || self.source_ready != ready
                || self.source_query != state.page.query.search
            {
                self.source_rows = source_rows(&state);
                self.source_snapshot = Some(state.sources.clone());
                self.source_account = state.me;
                self.source_ready = ready;
                self.source_generation += 1;
                self.source_query = state.page.query.search.clone();
            }
        } else {
            self.source_snapshot = None;
            self.source_rows.clear();
        }
        let sources = &self.source_rows;
        let playlists = if *screen == data::Screen::Playlists {
            playlist_indices(&state)
        } else {
            Vec::new()
        };
        let count = match screen {
            data::Screen::Sources => sources.len(),
            data::Screen::Playlists => playlists.len(),
            _ => state.page.total,
        };
        let mut need_page = None;
        let mut artwork_ids = Vec::new();
        let mut source_photo_ids = Vec::new();
        if self.row_icons.len() > 128 {
            self.row_icons.clear();
        }
        if self.playlist_art.len() > 128 {
            self.playlist_art.clear();
        }
        if self.source_layout.len() > 128 {
            self.source_layout.clear();
        }
        while let Some(step) = self.view.draw_walk(cx, scope, walk).step() {
            if let Some(mut list) = step.as_portal_list().borrow_mut() {
                list.set_item_range(cx, 0, count.max(1) + 1);
                while let Some(index) = list.next_visible_item(cx) {
                    if index >= count.max(1) {
                        let mut end = list.item(cx, index, id!(End));
                        let height = if self.reserve_dock { 150.0 } else { 16.0 };
                        script_apply_eval!(cx,end,{height:#(height)});
                        end.draw_all_unscoped(cx);
                        continue;
                    }
                    if count == 0 {
                        let item = list.item(cx, index, id!(Empty));
                        let (title, subtitle) = if !state.page.query.search.is_empty()
                            || *screen == data::Screen::OnDevice
                        {
                            ("No results", "Try another search or source.")
                        } else {
                            match screen {
                                data::Screen::Favorites => (
                                    "Keep what you love",
                                    "Tap the heart next to a song to find it here.",
                                ),
                                data::Screen::Playlists => {
                                    ("No playlists yet.", "Create a playlist for your music.")
                                }
                                data::Screen::Playlist(_) => (
                                    "Add some music?",
                                    "Choose several songs from your library at once.",
                                ),
                                data::Screen::Profile(id) => (
                                    "No music available on this profile",
                                    if *id == state.me {
                                        "Add songs to your profile in Telegram, then refresh this list."
                                    } else {
                                        "The profile may have no songs, or its privacy settings may hide them from you."
                                    },
                                ),
                                data::Screen::PlaylistEditor(_) => (
                                    "All available tracks are already in this playlist, or your library is empty.",
                                    "",
                                ),
                                data::Screen::Sources => (
                                    "Connect Telegram",
                                    "Import audio files or connect Telegram.",
                                ),
                                _ => (
                                    "Your next favorite song starts here.",
                                    "Import audio files or connect Telegram.",
                                ),
                            }
                        };
                        item.label(cx, ids!(title))
                            .set_text(cx, &crate::i18n::tr(&state.language, title));
                        item.label(cx, ids!(subtitle))
                            .set_text(cx, &crate::i18n::tr(&state.language, subtitle));
                        item.widget(cx, ids!(subtitle))
                            .set_visible(cx, !subtitle.is_empty());
                        let editor = matches!(screen, data::Screen::PlaylistEditor(_));
                        item.widget(cx, ids!(empty_library))
                            .set_visible(cx, !editor && *screen != data::Screen::Favorites);
                        item.widget(cx, ids!(empty_favorite))
                            .set_visible(cx, *screen == data::Screen::Favorites);
                        item.widget(cx, ids!(empty_add))
                            .set_visible(cx, matches!(screen, data::Screen::Playlist(_)));
                        item.widget(cx, ids!(empty_profile)).set_visible(
                            cx,
                            matches!(screen, data::Screen::Profile(_))
                                && state.page.query.search.is_empty(),
                        );
                        item.widget(cx, ids!(empty_add))
                            .set_text(cx, &crate::i18n::tr(&state.language, "Add recordings"));
                        item.widget(cx, ids!(empty_profile))
                            .set_text(cx, &crate::i18n::tr(&state.language, "Open in Telegram"));
                        let mut label = item.widget(cx, ids!(title));
                        let font = if editor { 12.0 } else { 16.5 };
                        script_apply_eval!(cx,label,{draw_text.text_style.font_size:#(font)});
                        let library_empty =
                            matches!(screen, data::Screen::Music | data::Screen::Sources)
                                && state.page.query.search.is_empty();
                        item.widget(cx, ids!(empty_connect)).set_visible(
                            cx,
                            library_empty && state.auth.kind != "authorizationStateReady",
                        );
                        item.widget(cx, ids!(empty_import))
                            .set_visible(cx, library_empty && *screen != data::Screen::Sources);
                        item.widget(cx, ids!(empty_connect))
                            .set_text(cx, &crate::i18n::tr(&state.language, "Connect Telegram"));
                        item.widget(cx, ids!(empty_import))
                            .set_text(cx, &crate::i18n::tr(&state.language, "Import audio files"));
                        item.draw_all_unscoped(cx);
                        continue;
                    }
                    match screen {
                        data::Screen::Queue => {
                            let item = list.item(cx, index, id!(QueueEntry));
                            if let Some(track) = index
                                .checked_sub(state.page.offset)
                                .and_then(|i| state.page.tracks.get(i))
                            {
                                item.label(cx, ids!(title)).set_text(cx, &track.title);
                                item.label(cx, ids!(artist))
                                    .set_text(cx, &subtitle(&state.language, track));
                                let current =
                                    track.queue_position == Some(state.playback.queue_index);
                                let light = state.settings["theme"] == "light";
                                let color = if current {
                                    if light {
                                        vec4(0.075, 0.482, 0.541, 1.)
                                    } else {
                                        vec4(0.345, 0.788, 0.824, 1.)
                                    }
                                } else if light {
                                    vec4(0.114, 0.188, 0.196, 1.)
                                } else {
                                    vec4(0.945, 0.965, 0.953, 1.)
                                };
                                let mut title = item.widget(cx, ids!(title));
                                script_apply_eval!(cx,title,{draw_text.color:#(color)});
                                item.widget(cx, ids!(queue_up))
                                    .set_visible(cx, state.playback.order != data::Order::Shuffle);
                                item.widget(cx, ids!(queue_up)).set_disabled(cx, index == 0);
                            } else {
                                need_page = Some(index.saturating_sub(32));
                            }
                            item.draw_all_unscoped(cx);
                        }
                        data::Screen::Sources => {
                            if let Some(SourceEntry::Heading(title)) = sources.get(index) {
                                let item = list.item(cx, index, id!(SourceHeading));
                                item.label(cx, ids!(title))
                                    .set_text(cx, &crate::i18n::tr(&state.language, title));
                                item.draw_all_unscoped(cx);
                                continue;
                            }
                            let item = list.item(cx, index, id!(Source));
                            let (source, position, length) = match sources.get(index) {
                                Some(SourceEntry::Source {
                                    index,
                                    position,
                                    length,
                                }) => (state.sources.get(*index), *position, *length),
                                _ => (None, 0, 1),
                            };
                            if self.source_layout.get(&item.widget_uid())
                                != Some(&(index, self.source_generation))
                            {
                                self.source_layout
                                    .insert(item.widget_uid(), (index, self.source_generation));
                                let first = if position == 0 { 1.0 } else { 0.0 };
                                let last = if position + 1 == length { 1.0 } else { 0.0 };
                                let height = if source.is_none() { 76.0 } else { 64.0 };
                                let offset = position as f64 * 64.;
                                let group_height = length as f64 * height;
                                let accent = if source.is_none() { 1.0 } else { 0.0 };
                                let mut row = item.clone();
                                script_apply_eval!(cx,row,{height:#(height) draw_bg +:{first:#(first) last:#(last) group_offset:#(offset) group_height:#(group_height) accent:#(accent)}});
                            }
                            if let Some(source) = source {
                                source_photo_ids.push((source.id, source.profile));
                                item.label(cx, ids!(cover.initials))
                                    .set_text(cx, &initials(&source.title));
                                item.label(cx, ids!(title)).set_text(cx, &source.title);
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &format!(
                                        "{}{}",
                                        crate::i18n::tracks(&state.language, source.count),
                                        if source.profile {
                                            crate::i18n::tr(&state.language, " · In library")
                                        } else {
                                            "".into()
                                        }
                                    ),
                                );
                                set_image(&item, cx, ids!(cover.art), &source.photo);
                                set_icon(
                                    &item,
                                    cx,
                                    ids!(select),
                                    include_str!("../resources/more.svg"),
                                );
                            } else {
                                source_photo_ids.push((state.me, true));
                                let own =
                                    state.sources.iter().find(|s| s.profile && s.id == state.me);
                                let name = own.map(|s| initials(&s.title)).unwrap_or_else(|| {
                                    crate::i18n::tr(&state.language, "Me").into_owned()
                                });
                                item.label(cx, ids!(cover.initials)).set_text(cx, &name);
                                item.label(cx, ids!(title))
                                    .set_text(cx, &crate::i18n::tr(&state.language, "My profile"));
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &crate::i18n::tr(&state.language, "Music in Telegram"),
                                );
                                set_icon(
                                    &item,
                                    cx,
                                    ids!(select),
                                    include_str!("../resources/chevron.svg"),
                                );
                                set_image(
                                    &item,
                                    cx,
                                    ids!(cover.art),
                                    own.map(|s| s.photo.as_str()).unwrap_or(""),
                                );
                            }
                            item.draw_all_unscoped(cx);
                        }
                        data::Screen::Playlists => {
                            let item = list.item(cx, index, id!(Playlist));
                            if let Some(playlist) = playlists
                                .get(index)
                                .and_then(|index| state.playlists.get(*index))
                            {
                                item.label(cx, ids!(title)).set_text(cx, &playlist.name);
                                item.label(cx, ids!(subtitle)).set_text(
                                    cx,
                                    &crate::i18n::tracks(&state.language, playlist.count),
                                );
                                if self.playlist_art.get(&item.widget_uid())
                                    != Some(&playlist.covers)
                                {
                                    self.playlist_art
                                        .insert(item.widget_uid(), playlist.covers.clone());
                                    let artwork =
                                        playlist.covers.iter().any(|cover| !cover.path.is_empty());
                                    item.widget(cx, ids!(single))
                                        .set_visible(cx, artwork && playlist.covers.len() == 1);
                                    item.widget(cx, ids!(mosaic))
                                        .set_visible(cx, artwork && playlist.covers.len() > 1);
                                    if let Some(cover) = playlist.covers.first() {
                                        set_image(&item, cx, ids!(single.art), &cover.path);
                                        set_cover(&item, cx, ids!(single), &cover.title);
                                    }
                                    for (index, path) in
                                        [ids!(art0), ids!(art1), ids!(art2), ids!(art3)]
                                            .iter()
                                            .enumerate()
                                    {
                                        let widget = item.widget(cx, *path);
                                        if let Some(cover) = playlist.covers.get(index) {
                                            set_cover(&item, cx, *path, &cover.title);
                                            set_image(&widget, cx, ids!(art), &cover.path);
                                        } else {
                                            set_image(&widget, cx, ids!(art), "");
                                        }
                                    }
                                }
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
                                item.label(cx, ids!(artist))
                                    .set_text(cx, &subtitle(&state.language, track));
                                item.label(cx, ids!(duration)).set_text(
                                    cx,
                                    &if track.duration > 0 {
                                        data::seconds(track.duration as f64)
                                    } else {
                                        "—".into()
                                    },
                                );
                                let favorite = item.widget(cx, ids!(favorite));
                                let light = state.settings["theme"] == "light";
                                let playing = state
                                    .playback
                                    .track
                                    .as_ref()
                                    .is_some_and(|t| t.id == track.id);
                                let selected = self.selection.contains(&track.id);
                                let flags = u8::from(playing) | u8::from(selected) * 2;
                                if self.row_icons.get(&favorite.widget_uid()).is_none_or(
                                    |(title, liked, theme, current)| {
                                        title != &track.title
                                            || *liked != track.liked
                                            || *theme != light
                                            || *current != flags
                                    },
                                ) {
                                    self.row_icons.insert(
                                        favorite.widget_uid(),
                                        (track.title.clone(), track.liked, light, flags),
                                    );
                                    let color =
                                        vec4(0.08, 0.48, 0.54, if selected { 0.14 } else { 0.0 });
                                    let mut highlight = item.widget(cx, ids!(highlight));
                                    script_apply_eval!(cx,highlight,{draw_bg.color:#(color)});
                                    let color = if playing {
                                        if light {
                                            vec4(0.075, 0.482, 0.541, 1.)
                                        } else {
                                            vec4(0.345, 0.788, 0.824, 1.)
                                        }
                                    } else if light {
                                        vec4(0.114, 0.188, 0.196, 1.)
                                    } else {
                                        vec4(0.945, 0.965, 0.953, 1.)
                                    };
                                    let mut title = item.widget(cx, ids!(title));
                                    script_apply_eval!(cx,title,{draw_text.color:#(color)});
                                    let mut check = item.widget(cx, ids!(checkbox));
                                    let checked = if selected { 1.0 } else { 0.0 };
                                    script_apply_eval!(cx,check,{draw_bg.checked:#(checked)});
                                    set_icon(
                                        &item,
                                        cx,
                                        ids!(favorite),
                                        if track.liked {
                                            include_str!("../resources/favorite.svg")
                                        } else {
                                            include_str!("../resources/favorite_border.svg")
                                        },
                                    );
                                    set_cover(&item, cx, ids!(cover), &track.title);
                                    if let Some(mut button) = favorite.borrow_mut::<Button>() {
                                        button.draw_icon.color = if track.liked {
                                            if light {
                                                vec4(0.72, 0.278, 0.314, 1.)
                                            } else {
                                                vec4(1.0, 0.635, 0.635, 1.0)
                                            }
                                        } else if state.settings["theme"] == "light" {
                                            vec4(0.349, 0.396, 0.459, 1.)
                                        } else {
                                            vec4(0.690, 0.722, 0.765, 1.)
                                        };
                                    }
                                }
                                item.widget(cx, ids!(saved))
                                    .set_visible(cx, track.saved || track.imported());
                                item.widget(cx, ids!(selection))
                                    .set_visible(cx, self.selecting);
                                item.widget(cx, ids!(favorite))
                                    .set_visible(cx, !self.selecting);
                                item.widget(cx, ids!(more)).set_visible(cx, !self.selecting);
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
        if state.auth.kind == "authorizationStateReady"
            && source_photo_ids != self.source_photo_ids
            && client
                .commands
                .try_send(data::Command::SourcePhotos(source_photo_ids.clone()))
                .is_ok()
        {
            self.source_photo_ids = source_photo_ids;
        }
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        self.view.handle_event(cx, event, scope);
    }
}

impl Catalog {
    pub fn refresh_photos(&mut self) {
        self.source_photo_ids.clear();
    }
    pub fn refresh_style(&mut self) {
        self.requested = None;
        self.row_icons.clear();
        self.playlist_art.clear();
        self.source_layout.clear();
        self.source_photo_ids.clear();
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

#[derive(Default)]
struct IconCache {
    clock: u64,
    glyphs: HashMap<WidgetUid, (&'static str, u64)>,
}

thread_local! {
    // No widget references are retained. Recent use keeps persistent controls
    // alive in this bounded cache while virtual rows come and go.
    static ICONS: RefCell<IconCache> = RefCell::default();
}

fn apply_icon(uid: WidgetUid, icon: &mut DrawSvg, svg: &'static str) -> bool {
    let changed = ICONS.with(|cache| {
        let mut cache = cache.borrow_mut();
        cache.clock = cache.clock.wrapping_add(1);
        let clock = cache.clock;
        let previous = cache.glyphs.get(&uid).map(|(source, _)| *source);
        let changed = icon.svg.is_some()
            || icon.svg_doc.is_none()
            || previous.is_none_or(|previous| !std::ptr::eq(previous, svg));
        if previous.is_none() && cache.glyphs.len() >= 1024 {
            if let Some(oldest) = cache
                .glyphs
                .iter()
                .min_by_key(|(_, (_, used))| *used)
                .map(|(id, _)| *id)
            {
                cache.glyphs.remove(&oldest);
            }
        }
        cache.glyphs.insert(uid, (svg, clock));
        changed
    });
    if changed {
        icon.svg = None;
        icon.load_from_str(svg);
    }
    changed
}

pub fn set_icon(ui: &WidgetRef, cx: &mut Cx, path: &[LiveId], svg: &'static str) {
    let widget = ui.widget(cx, path);
    let uid = widget.widget_uid();
    let changed = if let Some(mut button) = widget.borrow_mut::<Button>() {
        apply_icon(uid, &mut button.draw_icon, svg)
    } else if let Some(mut button) = widget.borrow_mut::<GlassButton>() {
        apply_icon(uid, &mut button.draw_icon, svg)
    } else {
        false
    };
    if changed {
        widget.redraw(cx);
    }
}

pub fn refresh_icon(ui: &WidgetRef, cx: &mut Cx, path: &[LiveId]) {
    let uid = ui.widget(cx, path).widget_uid();
    let source = ICONS.with(|cache| cache.borrow().glyphs.get(&uid).map(|(source, _)| *source));
    if let Some(source) = source {
        set_icon(ui, cx, path, source);
    }
}

pub fn set_cover(ui: &WidgetRef, cx: &mut Cx, path: &[LiveId], title: &str) {
    let hash = title
        .encode_utf16()
        .fold(0i32, |hash, c| hash.wrapping_mul(31).wrapping_add(c as i32));
    let hue = (i64::from(hash).abs() % 6) as f64;
    let mut cover = ui.widget(cx, path);
    script_apply_eval!(cx,cover,{draw_bg.hue:#(hue)});
}

pub fn initials(title: &str) -> String {
    title
        .split_whitespace()
        .take(2)
        .filter_map(|word| word.chars().next())
        .flat_map(char::to_uppercase)
        .collect()
}

pub fn subtitle<'a>(language: &str, track: &'a data::Track) -> std::borrow::Cow<'a, str> {
    if track.artist.is_empty() && track.imported() {
        crate::i18n::tr(language, "From device")
    } else {
        std::borrow::Cow::Borrowed(track.subtitle())
    }
}

#[derive(Script, ScriptHook, Widget)]
pub struct Choices {
    #[deref]
    view: View,
    #[live]
    layout_mode: u32,
    #[rust]
    pub rows: Vec<ChoiceRow>,
    #[rust]
    requested: Vec<(i64, bool)>,
}

#[derive(Default)]
pub struct ChoiceRow {
    pub title: String,
    pub subtitle: String,
    pub photo: String,
    pub key: Option<(i64, bool)>,
    pub heading: bool,
    pub library: bool,
    pub checked: bool,
    pub glass: bool,
    pub radio: bool,
}

impl Choices {
    pub fn refresh_photos(&mut self) {
        self.requested.clear();
    }
}

impl Widget for Choices {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        let client = runtime::start();
        let state = client.snapshot.load_full();
        let mut keys = Vec::new();
        while let Some(step) = self.view.draw_walk(cx, scope, walk).step() {
            if let Some(mut list) = step.as_portal_list().borrow_mut() {
                list.set_item_range(cx, 0, self.rows.len());
                while let Some(index) = list.next_visible_item(cx) {
                    if index >= self.rows.len() {
                        continue;
                    }
                    let row = &self.rows[index];
                    let template = if row.heading {
                        id!(Heading)
                    } else if self.layout_mode == 1 {
                        id!(Chip)
                    } else if self.layout_mode == 2 {
                        id!(Quick)
                    } else {
                        id!(Choice)
                    };
                    let item = list.item(cx, index, template);
                    item.label(cx, ids!(title)).set_text(cx, &row.title);
                    if !row.heading {
                        if self.layout_mode != 1
                            && let Some(key) = row.key
                        {
                            keys.push(key);
                        }
                        item.widget(cx, ids!(checked))
                            .set_visible(cx, row.checked && !row.radio);
                        item.widget(cx, ids!(radio)).set_visible(cx, row.radio);
                        let mut radio = item.widget(cx, ids!(radio));
                        let checked = if row.checked { 1.0 } else { 0.0 };
                        script_apply_eval!(cx,radio,{draw_bg.checked:#(checked)});
                        item.widget(cx, ids!(surface)).set_visible(cx, row.glass);
                        if row.glass {
                            let first = if index == 0 || self.rows[index - 1].heading || index == 1
                            {
                                1.0
                            } else {
                                0.0
                            };
                            let last = if index == 0
                                || self.rows.get(index + 1).is_none_or(|r| r.heading)
                            {
                                1.0
                            } else {
                                0.0
                            };
                            let mut surface = item.widget(cx, ids!(surface));
                            script_apply_eval!(cx,surface,{draw_bg +:{first:#(first) last:#(last) accent:#(checked)}});
                        }
                        let mut highlight = item.widget(cx, ids!(highlight));
                        let color = vec4(
                            0.08,
                            0.48,
                            0.54,
                            if row.checked && !row.glass { 0.12 } else { 0.0 },
                        );
                        script_apply_eval!(cx,highlight,{draw_bg.color:#(color)});
                        item.widget(cx, ids!(subtitle))
                            .set_visible(cx, !row.subtitle.is_empty());
                        item.label(cx, ids!(subtitle)).set_text(cx, &row.subtitle);
                        item.widget(cx, ids!(avatar))
                            .set_visible(cx, row.key.is_some());
                        item.widget(cx, ids!(library)).set_visible(cx, row.library);
                        if self.layout_mode == 1 {
                            let mut chip = item.clone();
                            let active = if row.checked { 1.0 } else { 0.0 };
                            script_apply_eval!(cx,chip,{draw_bg.accent:#(active)});
                        }
                        let photo = row
                            .key
                            .and_then(|key| state.avatar_paths.get(&key))
                            .unwrap_or(&row.photo);
                        set_image(&item, cx, ids!(avatar.art), photo);
                        item.label(cx, ids!(avatar.initials))
                            .set_text(cx, &initials(&row.title));
                    }
                    item.draw_all_unscoped(cx);
                }
            }
        }
        if keys.is_empty() {
            // Folder chips and ordinary menus share Choices with avatar rows.
            // They must not cancel another visible list's avatar interests.
            self.requested.clear();
        } else if state.auth.kind == "authorizationStateReady"
            && keys != self.requested
            && client
                .commands
                .try_send(data::Command::SourcePhotos(keys.clone()))
                .is_ok()
        {
            self.requested = keys;
        }
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        self.view.handle_event(cx, event, scope);
    }
}
