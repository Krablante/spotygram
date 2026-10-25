use makepad_widgets::*;

// Dimensions and optical treatment follow shared/Design.kt in the stable app.
// Stationary controls evaluate the backdrop directly. Reading panels use the
// existing GPU capture and keep their children in the same overlay draw list.
script_mod! {
    use mod.prelude.widgets.*
    use mod.widgets.*
    use mod.widgets.glass.*

    mod.spotygram = {}
    // SVGs retain an unpainted 24x24 rectangle: DrawSvg otherwise crops the
    // viewport to the path and enlarges narrow glyphs such as Play and arrows.
    mod.spotygram.Mark = Icon{icon_walk:Walk{width:Fill height:Fill}}
    // Makepad uses points: 12 pt renders at the reference 16 sp size at 1x.
    mod.spotygram.Title = Label {
        width:Fill height:Fit padding:0 max_lines:1 text_overflow:Ellipsis
        draw_text +:{color:theme.color_text text_style:theme.font_bold{font_size:18 line_spacing:1.0}}
    }
    mod.spotygram.Body = Label {
        width:Fill height:22 padding:0 max_lines:1 text_overflow:Ellipsis
        draw_text +:{color:theme.color_text text_style:theme.font_regular{font_size:12 line_spacing:1.0}}
    }
    mod.spotygram.Caption = Label {
        width:Fill height:18 padding:0 max_lines:1 text_overflow:Ellipsis
        draw_text +:{color:theme.color_text_meta text_style:theme.font_regular{font_size:10.5 line_spacing:1.0}}
    }
    mod.spotygram.Prism = View {
        width:Fill height:Fill show_bg:true
        draw_bg +:{
            color:theme.color_bg_app
            dark:uniform(theme.spotygram_dark)
            pixel:fn(){
                let p=self.pos*self.rect_size;
                let w=self.rect_size.x;
                let h=self.rect_size.y;
                let a=clamp(1.0-length(p-vec2(w*0.03,h*0.21))/min(w*0.40,220.0),0.0,1.0);
                let b=clamp(1.0-length(p-vec2(w*1.04,h*0.24))/min(w*0.35,220.0),0.0,1.0);
                let c=clamp(1.0-length(p-vec2(w*0.04,h*0.96))/min(w*0.46,220.0),0.0,1.0);
                let d=clamp(1.0-length(p-vec2(w*0.48,h*1.02))/min(w*0.32,220.0),0.0,1.0);
                let e=clamp(1.0-length(p-vec2(w*1.02,h*0.92))/min(w*0.39,220.0),0.0,1.0);
                let alpha=mix(0.13,0.17,self.dark);
                var col=mix(self.color.rgb,vec3(0.345,0.788,0.824),a*alpha);
                col=mix(col,vec3(0.965,0.686,0.345),b*alpha);
                col=mix(col,vec3(0.369,0.616,0.922),c*alpha);
                col=mix(col,vec3(0.447,0.788,0.529),d*alpha);
                return vec4(mix(col,vec3(0.941,0.443,0.459),e*alpha),1.0);
            }
        }
    }
    mod.spotygram.CapturedSurface = RippleLensRoundedView {
        width:Fill height:Fit
        draw_bg +:{
            dark:uniform(theme.spotygram_dark)
            accent:uniform(0.0)
            base_color:uniform(theme.color_bg_app)
            accent_color:uniform(theme.color_primary)
            environment:uniform(1.0)
            first:uniform(1.0)
            last:uniform(1.0)
            group_offset:uniform(0.0)
            group_height:uniform(0.0)
            diffusion:uniform(0.10)
            relief:uniform(1.0)
            corner_radius:12.0 blur_level:1.0
            lensing_strength:8.0 lensing_width:12.0 diffraction_strength:0.55
            shadow_color:#x00000012 shadow_radius:5.0 shadow_offset:vec2(0.0,2.0)
            environment_color:fn(uv:vec2,size:vec2)->vec3{
                let p=uv*size;
                let w=size.x;
                let h=size.y;
                let alpha=mix(0.13,0.17,self.dark);
                let a=clamp(1.0-length(p-vec2(w*0.03,h*0.21))/min(w*0.40,220.0),0.0,1.0);
                let b=clamp(1.0-length(p-vec2(w*1.04,h*0.24))/min(w*0.35,220.0),0.0,1.0);
                let c=clamp(1.0-length(p-vec2(w*0.04,h*0.96))/min(w*0.46,220.0),0.0,1.0);
                let d=clamp(1.0-length(p-vec2(w*0.48,h*1.02))/min(w*0.32,220.0),0.0,1.0);
                let e=clamp(1.0-length(p-vec2(w*1.02,h*0.92))/min(w*0.39,220.0),0.0,1.0);
                var col=mix(self.base_color.rgb,vec3(0.345,0.788,0.824),a*alpha);
                col=mix(col,vec3(0.965,0.686,0.345),b*alpha);
                col=mix(col,vec3(0.369,0.616,0.922),c*alpha);
                col=mix(col,vec3(0.447,0.788,0.529),d*alpha);
                return mix(col,vec3(0.941,0.443,0.459),e*alpha);
            }
            optical_rim:fn(y:float)->vec4{
                let t=clamp(y/0.52,0.0,1.0)*3.0;
                let top=vec4(1.0,1.0,1.0,mix(0.95,0.20,self.dark));
                let cyan=vec4(0.345,0.788,0.824,0.12);
                let shade=vec4(0.0,0.0,0.0,mix(0.12,0.08,self.dark));
                let bottom=vec4(1.0,1.0,1.0,mix(0.72,0.20,self.dark));
                if t<1.0 {return mix(top,cyan,t);}
                if t<2.0 {return mix(cyan,shade,t-1.0);}
                return mix(shade,bottom,t-2.0);
            }
            outer_shadow:fn(shape:float,coverage:float)->float{
                // The quad has 4 pt of padding. A Gaussian alone still has
                // visible alpha there and exposes its rectangular boundary.
                // End the halo smoothly within that support on every surface.
                let distance=max(shape,0.0);
                let fade=1.0-smoothstep(1.0,4.0,distance);
                return exp(-distance*distance/12.0)*fade*(1.0-coverage);
            }
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size3);
                let p=self.sdf_rect_pos;
                let size=self.sdf_rect_size;
                let local=self.pos*self.rect_size3-p;
                let cap=mix(self.first,self.last,step(size.y*0.5,local.y));
                let side=max(1.0-smoothstep(0.0,5.0,local.x),smoothstep(size.x-5.0,size.x,local.x));
                let edge_alpha=max(cap,side);
                let group_y=p.y-self.group_offset;
                let group_height=max(size.y,self.group_height);
                sdf.box(p.x,group_y,size.x,group_height,self.corner_radius);
                let shape=sdf.shape;
                let depth=max(-shape,0.0);
                let normal=self.rounded_edge_normal(shape);
                let source=max(self.source_size,vec2(1.0));
                let uv=(self.rect_pos2+self.pos*self.rect_size3)/source;
                let edge=pow(clamp(1.0-depth/12.0,0.0,1.0),1.45)*edge_alpha;
                let bend=normal*edge*self.lensing_strength*(1.0-clamp(self.press_flatten,0.0,1.0)*0.4)/source;
                let split=normal*edge*self.diffraction_strength/source;
                let red=self.sample_gauss(clamp(uv+bend+split,vec2(0.0),vec2(1.0)));
                let green=self.sample_gauss(clamp(uv+bend,vec2(0.0),vec2(1.0)));
                let blue=self.sample_gauss(clamp(uv+bend-split,vec2(0.0),vec2(1.0)));
                let sampled=mix(self.base_color.rgb,vec3(red.r,green.g,blue.b),self.has_gauss);
                let backdrop=mix(sampled,self.environment_color(uv+bend,source),self.environment);
                let tint=mix(vec3(1.0),vec3(0.094,0.137,0.184),self.dark);
                var material=mix(backdrop,tint,self.diffusion);
                material=mix(material,self.accent_color.rgb,self.accent*0.14);
                let y=clamp((local.y+self.group_offset)/max(max(size.y,self.group_height),1.0),0.0,1.0);
                material=mix(material,vec3(1.0),mix(0.26,0.10,self.dark)*(1.0-y)*(1.0-y)*self.relief);
                material=material*(1.0-y*mix(0.035,0.12,self.dark)*self.relief);
                let shadow=(1.0-smoothstep(0.0,3.5,depth))*max(normal.y,0.0)*mix(0.12,0.20,self.dark)*edge_alpha*self.relief;
                sdf.fill_keep(vec4(material*(1.0-shadow),1.0));
                sdf.stroke(vec4(mix(vec3(0.46,0.57,0.58),vec3(0.88),self.dark),mix(0.28,0.20,self.dark)*edge_alpha*self.relief),0.75);
                sdf.box(p.x+1.2,group_y+1.2,size.x-2.4,group_height-2.4,max(self.corner_radius-0.6,0.5));
                let outer=mix(0.90,0.40,self.dark)*(0.45+0.55*abs(1.0-y*2.0));
                sdf.stroke(vec4(1.0,1.0,1.0,outer*edge_alpha*self.relief),0.8);
                sdf.box(p.x+2.6,group_y+2.6,size.x-5.2,group_height-5.2,max(self.corner_radius-1.3,0.5));
                let rim=self.optical_rim(y);
                sdf.stroke(vec4(rim.rgb,rim.a*edge_alpha*self.relief),mix(0.8,0.5,self.dark));
                let shade=self.outer_shadow(shape,sdf.result.a)*0.08;
                return (sdf.result+vec4(0.0,0.0,0.0,shade))*self.layer_opacity;
            }
        }
    }
    // Sdf2d doubles box radii and stroke widths. Keep optical dimensions in
    // those units; the public component dimensions below remain layout points.
    // Stationary controls refract the same analytic light field as Prism.
    // They need no scene capture or blur pyramid while the catalog scrolls.
    mod.spotygram.Surface = View {
        width:Fill height:Fit show_bg:true clip_x:false clip_y:false
        draw_bg +:{
            color:theme.color_bg_app
            base_color:uniform(theme.color_bg_app)
            accent_color:uniform(theme.color_primary)
            dark:uniform(theme.spotygram_dark)
            accent:uniform(0.0) diffusion:uniform(0.10)
            first:uniform(1.0) last:uniform(1.0)
            group_offset:uniform(0.0) group_height:uniform(0.0)
            corner_radius:uniform(-1.0) border_radius:uniform(24.0)
            hover:uniform(0.0) down:uniform(0.0) focus:uniform(0.0) disabled:uniform(0.0)
            loading:uniform(0.0) anim_time:uniform(0.0) loading_color:uniform(theme.color_text)
            environment_color:mod.spotygram.CapturedSurface.draw_bg.environment_color
            optical_rim:mod.spotygram.CapturedSurface.draw_bg.optical_rim
            outer_shadow:mod.spotygram.CapturedSurface.draw_bg.outer_shadow
            vertex:fn(){
                let position=self.clip_and_transform_vertex(self.rect_pos-vec2(4.0),self.rect_size+vec2(8.0));
                self.pos=(self.pos*(self.rect_size+vec2(8.0))-vec2(4.0))/self.rect_size;
                return position;
            }
            pixel:fn(){
                let size=self.rect_size;
                let local=(self.pos-vec2(0.5))*size/(1.0-self.down*0.03)+size*0.5;
                let sdf=Sdf2d.viewport(local);
                let cap=mix(self.first,self.last,step(size.y*0.5,local.y));
                let radius=mix(self.border_radius*0.5,self.corner_radius,step(0.0,self.corner_radius));
                let side=max(1.0-smoothstep(0.0,5.0,local.x),smoothstep(size.x-5.0,size.x,local.x));
                let seal=max(cap,side);
                // Adjacent rows share one contour. Their internal edges must
                // stay opaque, including the antialiasing support outside a row.
                let group_y=-self.group_offset;
                let group_height=max(size.y,self.group_height);
                sdf.box(0.0,group_y,size.x,group_height,max(radius,0.0));
                let shape=sdf.shape;
                let depth=max(-sdf.shape,0.0);
                let gradient=vec2(dFdx(sdf.shape),dFdy(sdf.shape));
                let normal=gradient/max(length(gradient),0.0001);
                let edge=pow(clamp(1.0-depth/12.0,0.0,1.0),1.45)*seal;
                let source=max(vec2(2.0/abs(self.draw_pass.camera_projection[0].x*self.draw_pass.camera_view[0].x),2.0/abs(self.draw_pass.camera_projection[1].y*self.draw_pass.camera_view[1].y)),vec2(1.0));
                let uv=(self.rect_pos+self.pos*size+normal*edge*8.0*(1.0-self.down*0.1))/source;
                let backdrop=self.environment_color(uv,source);
                let tint=mix(vec3(1.0),vec3(0.094,0.137,0.184),self.dark);
                var material=mix(backdrop,tint,self.diffusion);
                material=clamp(material+self.color.rgb-self.base_color.rgb,vec3(0.0),vec3(1.0));
                material=mix(material,self.accent_color.rgb,self.accent*0.14);
                material=mix(material,vec3(1.0),self.hover*0.025+self.focus*0.02);
                let y=clamp((local.y+self.group_offset)/max(max(size.y,self.group_height),1.0),0.0,1.0);
                material=mix(material,vec3(1.0),mix(0.26,0.10,self.dark)*(1.0-y)*(1.0-y));
                material=material*(1.0-y*mix(0.035,0.12,self.dark));
                let shadow=(1.0-smoothstep(0.0,3.5,depth))*max(normal.y,0.0)*mix(0.12,0.20,self.dark)*seal;
                // Match the reference Haze light material's contrast and white
                // point before its specular rims; flat white loses that depth.
                let reference=clamp(material*1.174-vec3(0.190),vec3(0.0),vec3(1.0));
                material=mix(reference,material,self.dark);
                sdf.fill_keep(vec4(material*(1.0-shadow),1.0));
                sdf.stroke(vec4(mix(vec3(0.46,0.57,0.58),vec3(0.88),self.dark),mix(0.28,0.20,self.dark)*seal),0.75);
                sdf.box(1.2,group_y+1.2,size.x-2.4,group_height-2.4,max(radius-0.6,0.5));
                sdf.stroke(vec4(1.0,1.0,1.0,mix(0.90,0.40,self.dark)*(0.45+0.55*abs(1.0-y*2.0))*seal),0.8);
                sdf.box(2.6,group_y+2.6,size.x-5.2,group_height-5.2,max(radius-1.3,0.5));
                let rim=self.optical_rim(y);
                sdf.stroke(vec4(rim.rgb,rim.a*seal),mix(1.2,0.5,self.dark));
                if self.loading>0.0 {
                    let c=size*0.5;
                    let a=self.anim_time*6.2831853;
                    sdf.arc_round_caps(c.x,c.y,min(size.x,size.y)*0.23,a,a+4.712389,2.0);
                    sdf.fill(vec4(self.loading_color.rgb,self.loading));
                }
                let shade=self.outer_shadow(shape,sdf.result.a)*0.07*seal;
                return sdf.result+vec4(0.0,0.0,0.0,shade);
            }
        }
    }
    mod.spotygram.Glass = ButtonFlat {
        clip_x:false clip_y:false
        height:48 margin:0 padding:Inset{left:16 right:16 top:0 bottom:0} spacing:7
        text:"" align:Center label_walk:Walk{width:Fit height:Fit}
        draw_bg:mod.spotygram.Surface.draw_bg{}
        draw_text +:{color:theme.color_text color_hover:theme.color_text color_down:theme.color_text color_focus:theme.color_text color_disabled:mix(theme.color_text,theme.color_bg_app,0.62) text_style:theme.font_bold{font_size:10.5}}
        draw_icon +:{color:theme.color_text}
        icon_walk:Walk{width:22 height:22}
    }
    mod.spotygram.Icon = mod.spotygram.Glass {width:48 padding:12 text:""}
    mod.spotygram.PlainIcon = ButtonFlatter {
        width:48 height:48 padding:12 margin:0 text:""
        icon_walk:Walk{width:24 height:24}
        draw_icon +:{color:theme.color_text}
        draw_bg.loading_color:theme.color_text
        draw_bg.color_disabled:#x00000000
    }
    mod.spotygram.InlineGlass = mod.spotygram.Glass{padding:Inset{left:12 right:12 top:0 bottom:0}}
    mod.spotygram.InlineIcon = mod.spotygram.InlineGlass {width:48 padding:12 text:""}
    mod.spotygram.SpeedStep = mod.spotygram.PlainIcon {
        draw_bg +:{
            dark:uniform(theme.spotygram_dark)
            ink:uniform(theme.color_text)
            paper:uniform(theme.color_bg_app)
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                let center=self.rect_size*0.5;
                let radius=min(center.x,center.y)-1.0;
                let y=clamp(self.pos.y,0.0,1.0);
                let light=mix(vec3(1.0),self.paper.rgb,mix(0.28,0.70,y));
                let face=mix(light,mix(self.paper.rgb,self.ink.rgb,mix(0.08,0.035,y)),self.dark);
                let tint=mix(face,self.ink.rgb,self.dark*0.045+self.hover*0.04+self.down*0.08);
                sdf.circle(center.x,center.y,radius);
                sdf.fill_keep(vec4(tint,mix(0.62,0.92,self.dark)*(1.0-self.disabled*0.45)));
                sdf.stroke(vec4(self.ink.rgb,mix(0.10,0.16,self.dark)+self.focus*0.45),0.5+self.focus*0.25);
                sdf.circle(center.x,center.y,radius-1.0);
                sdf.stroke(vec4(1.0,1.0,1.0,mix(0.65,0.09,self.dark)*(1.0-y)),0.5);
                return sdf.result;
            }
        }
    }
    mod.spotygram.SpeedPreset = mod.spotygram.InlineGlass {
        width:Fill height:48 padding:2
        draw_text +:{
            accent:uniform(0.0)
            active:uniform(theme.color_primary)
            get_color:fn(){return mix(self.color,self.active,self.accent);}
        }
        draw_bg +:{
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                let size=self.rect_size;
                let active=self.accent;
                let ink=mix(self.color.rgb,self.accent_color.rgb,active);
                sdf.box(0.5,0.5,size.x-1.0,size.y-1.0,(size.y-1.0)*0.25);
                sdf.fill_keep(vec4(ink,active*mix(0.13,0.22,self.dark)+self.hover*0.04+self.down*0.07));
                sdf.stroke(vec4(ink,mix(0.20,0.40,self.dark)*(1.0-active)+self.focus*0.6),0.5+self.focus*0.25);
                return sdf.result;
            }
            color:theme.color_text
        }
    }
    mod.spotygram.Flat = ButtonFlatter {
        height:52 width:Fill margin:0 spacing:16
        padding:Inset{left:12 right:12 top:8 bottom:8}
        icon_walk:Walk{width:24 height:24}
        draw_icon +:{color:theme.color_text_meta}
        draw_bg.color_disabled:#x00000000
        label_walk:Walk{width:Fill height:Fit} label_align:Align{x:0.0}
        draw_text +:{
            color:theme.color_text color_disabled:mix(theme.color_text,theme.color_bg_app,0.62)
            text_style:theme.font_regular{font_size:12 line_spacing:1.0}
            get_color:fn(){
                let ink=mix(self.color,self.color_disabled,self.disabled);
                return vec4(ink.rgb,ink.a*(1.0-self.loading));
            }
        }
    }
    mod.spotygram.Nav = ButtonFlatter {
        width:Fill height:52 flow:Down spacing:1 margin:0 padding:Inset{top:6 bottom:4 left:0 right:0}
        align:Center icon_walk:Walk{width:24 height:24}
        draw_icon +:{color:theme.color_text_meta}
        draw_text +:{color:theme.color_text_meta color_disabled:mix(theme.color_text,theme.color_bg_app,0.62) text_style:theme.font_regular{font_size:8.25} get_color:mod.spotygram.Flat.draw_text.get_color}
        draw_bg +:{
            color_disabled:#x00000000
            active:uniform(0.0)
            color:theme.color_primary
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                sdf.box(0.7,0.7,self.rect_size.x-1.4,self.rect_size.y-1.4,13.0);
                let y=self.pos.y;
                let tint=mix(self.color.rgb,vec3(1.0),0.48*(1.0-y));
                sdf.fill_keep(vec4(tint,self.active*(0.14+0.07*y)));
                sdf.stroke(vec4(1.0,1.0,1.0,self.active*0.8),1.4);
                return sdf.result;
            }
        }
    }
    mod.spotygram.RailNav = mod.spotygram.Nav{height:68 padding:0 spacing:0 icon_walk:Walk{width:24 height:24} draw_text.text_style.font_size:7.5}
    mod.spotygram.DrawNavLens = set_type_default() do #(DrawNavLens::script_shader(vm)) {
        ..mod.draw.DrawQuad
        position:0.0 tint:#x086d79 vertical:0.0
        dark:uniform(theme.spotygram_dark)
        pixel:fn(){
            let sdf=Sdf2d.viewport(self.pos*self.rect_size);
            let span=mix(self.rect_size.x,self.rect_size.y,self.vertical)/4.0;
            let x=mix(self.position*span,0.0,self.vertical);
            let y=mix(0.0,self.position*span,self.vertical);
            let w=mix(span,self.rect_size.x,self.vertical);
            let h=mix(self.rect_size.y,span,self.vertical);
            sdf.box(x+0.7,y+0.7,w-1.4,h-1.4,13.0);
            let local_y=clamp((self.pos.y*self.rect_size.y-y)/max(h,1.0),0.0,1.0);
            let tint=mix(self.tint.rgb,vec3(1.0),mix(0.46,0.08,self.dark)*(1.0-local_y));
            sdf.fill_keep(vec4(tint,0.14+0.07*local_y));
            sdf.stroke(vec4(1.0,1.0,1.0,mix(0.92,0.44,self.dark)),1.2);
            sdf.box(x+2.2,y+2.2,w-4.4,h-4.4,12.25);
            sdf.stroke(vec4(self.tint.rgb,mix(0.18,0.12,self.dark)),0.8);
            return sdf.result;
        }
    }
    mod.spotygram.NavLens = #(NavLens::register_widget(vm)) {
        width:Fill height:Fill
        draw_bg:mod.spotygram.DrawNavLens{}
    }
    mod.spotygram.Input = TextInput {
        width:Fill height:48
        padding:Inset{left:16 right:14 top:13 bottom:10}
        draw_bg +:{border_radius:24.0 border_size:0.0 pixel:fn(){return vec4(0.0);}}
        draw_text +:{color:theme.color_text color_hover:theme.color_text color_focus:theme.color_text color_down:theme.color_text color_empty:theme.color_text_meta color_empty_hover:theme.color_text_meta color_empty_focus:theme.color_text_meta text_style.font_size:12}
        draw_cursor.color:theme.color_primary
        draw_composition_underline.color:theme.color_primary
    }
    mod.spotygram.Cover = RoundedView {
        width:44 height:44 flow:Overlay align:Center
        draw_bg +:{
            border_radius:10.0
            hue:instance(0.0)
            base_color:uniform(theme.color_bg_app)
            cover_color:fn()->vec3{
                var a=vec3(0.282,0.435,0.569);
                var b=vec3(0.447,0.788,0.529);
                if self.hue>0.5 {a=vec3(0.655,0.373,0.337);b=vec3(0.345,0.788,0.824);}
                if self.hue>1.5 {a=vec3(0.278,0.498,0.404);b=vec3(0.369,0.616,0.922);}
                if self.hue>2.5 {a=vec3(0.498,0.392,0.584);b=vec3(0.627,0.549,0.894);}
                if self.hue>3.5 {a=vec3(0.596,0.482,0.298);b=vec3(0.941,0.443,0.459);}
                if self.hue>4.5 {a=vec3(0.290,0.529,0.576);b=vec3(0.965,0.686,0.345);}
                let t=clamp((self.pos.x+self.pos.y)*0.5,0.0,1.0);
                let mid=mix(a,self.base_color.rgb,0.16);
                let end=mix(self.base_color.rgb,b,0.72);
                return mix(mix(a,mid,clamp(t*2.0,0.0,1.0)),end,clamp(t*2.0-1.0,0.0,1.0));
            }
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                sdf.box(0.0,0.0,self.rect_size.x,self.rect_size.y,self.border_radius*0.5);
                sdf.fill(vec4(self.cover_color(),1.0));
                return sdf.result;
            }
        }
        note := mod.spotygram.Mark{width:20 height:20 draw_icon +:{color:#xffffff99 svg:crate_resource("self:resources/music.svg")}}
        art := Image{width:Fill height:Fill fit:ImageFit.CropToFill downscale_to_drawn_size:true draw_bg +:{border_radius:5.0}}
    }
    // Clip each tile to the same 56 pt cover, keeping its exterior transparent
    // over the page's changing backdrop instead of painting a flat corner mask.
    mod.spotygram.PlaylistTile = mod.spotygram.Cover{width:28 height:28
        draw_bg:mod.spotygram.Cover.draw_bg{
            border_radius:0.0 tile:uniform(vec2(0.0))
            pixel:fn(){
                let p=(self.pos+self.tile)*self.rect_size;
                let q=abs(p-self.rect_size)-(self.rect_size-vec2(16.0));
                let d=min(max(q.x,q.y),0.0)+length(max(q,vec2(0.0)))-16.0;
                // Only the outside contour is antialiased. Fading the tile's
                // own rectangle exposes a cross through the mosaic interior.
                return vec4(self.cover_color(),1.0)*(1.0-smoothstep(-0.5,0.5,d));
            }
        }
        note.width:12 note.height:12
        art := Image{width:Fill height:Fill fit:ImageFit.CropToFill downscale_to_drawn_size:true
            draw_bg +:{
                tile:uniform(vec2(0.0))
                pixel:fn(){
                    let p=(self.pos+self.tile)*self.rect_size;
                    let q=abs(p-self.rect_size)-(self.rect_size-vec2(16.0));
                    let d=min(max(q.x,q.y),0.0)+length(max(q,vec2(0.0)))-16.0;
                    let mask=1.0-smoothstep(-0.5,0.5,d);
                    let color=mix(self.get_color(),vec4(0.2,0.2,0.2,1.0),self.async_load);
                    return Pal.premul(vec4(color.rgb,color.a*self.opacity*mask));
                }
            }
        }
    }
    mod.spotygram.Reflection = View{width:320 height:240 flow:Overlay show_bg:true
        draw_bg:mod.spotygram.Cover.draw_bg{
            shade:mod.spotygram.Cover.draw_bg.pixel
            pixel:fn(){
                let p=(self.pos-vec2(0.5))*vec2(2.0,2.0);
                return self.shade()*exp(-dot(p,p)*3.0)*0.08;
            }
        }
        art := Image{width:Fill height:Fill downscale_to_drawn_size:true fit:ImageFit.CropToFill
            draw_bg +:{pixel:fn(){
                let uv=self.pos*0.6+vec2(0.2);
                let a=self.image_texture.sample_as_bgra(uv)*0.20;
                let b=self.image_texture.sample_as_bgra(uv+vec2(0.25,0.0))*0.10+self.image_texture.sample_as_bgra(uv-vec2(0.25,0.0))*0.10;
                let c=self.image_texture.sample_as_bgra(uv+vec2(0.0,0.25))*0.10+self.image_texture.sample_as_bgra(uv-vec2(0.0,0.25))*0.10;
                let d=self.image_texture.sample_as_bgra(uv+vec2(0.25,0.25))*0.10+self.image_texture.sample_as_bgra(uv-vec2(0.25,0.25))*0.10;
                let e=self.image_texture.sample_as_bgra(uv+vec2(0.25,-0.25))*0.10+self.image_texture.sample_as_bgra(uv+vec2(-0.25,0.25))*0.10;
                let p=(self.pos-vec2(0.5))*vec2(2.0,2.0);
                let color=a+b+c+d+e;
                let alpha=exp(-dot(p,p)*3.0)*0.18;
                return vec4(color.rgb*alpha,alpha);
            }}
        }
    }
    mod.spotygram.Divider = SolidView{width:Fill height:1 margin:Inset{top:12 bottom:12} draw_bg.color:mix(theme.color_bg_app,theme.color_text,0.10)}
    mod.spotygram.Avatar = RoundedView{width:44 height:44 flow:Overlay align:Center
        draw_bg +:{border_radius:44.0 color:mix(theme.color_bg_app,theme.color_primary,0.28)}
        initials := Label{text:"" draw_text.color:theme.color_primary draw_text.text_style:theme.font_bold{font_size:13}}
        art := Image{width:Fill height:Fill fit:ImageFit.CropToFill downscale_to_drawn_size:true draw_bg.border_radius:44.0}
    }
    mod.spotygram.Section = mod.spotygram.Body{height:32 draw_text.text_style:theme.font_bold{font_size:12}}
    mod.spotygram.Preference = #(Preference::register_widget(vm)) {
        width:Fill height:Fit flow:Overlay
        row := View{width:Fill height:Fit flow:Right spacing:12 padding:Inset{left:12 right:12 top:14 bottom:14} align:Align{y:0.5}
            leading := View{width:24 height:24
                mark := mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text_meta}}
            }
            labels := View{width:Fill height:Fit flow:Down spacing:4
                title := mod.spotygram.Body{height:Fit max_lines:3 text:""}
                detail := mod.spotygram.Caption{height:Fit max_lines:6 text:""}
            }
            arrow := View{width:20 height:20
                mark := mod.spotygram.Mark{width:Fill height:Fill draw_icon +:{color:theme.color_text_meta svg:crate_resource("self:resources/chevron.svg")}}
            }
            toggle := View{visible:false width:60 height:48 show_bg:true
                draw_bg +:{
                    checked:uniform(0.0)
                    ink:uniform(theme.color_primary)
                    base_color:uniform(theme.color_bg_app)
                    meta_color:uniform(theme.color_text_meta)
                    dark:uniform(theme.spotygram_dark)
                    pixel:fn(){
                        let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                        sdf.box(3.0,9.0,54.0,30.0,7.5);
                        let base=mix(self.base_color.rgb,self.ink.rgb,0.04+self.checked*0.14);
                        let y=clamp((self.pos.y*self.rect_size.y-9.0)/30.0,0.0,1.0);
                        let track=mix(base,vec3(1.0),mix(0.20,0.065,self.dark)*(1.0-y));
                        sdf.fill_keep(vec4(track,1.0));
                        sdf.stroke(vec4(1.0,1.0,1.0,mix(0.88,0.38,self.dark)),1.0);
                        sdf.box(4.7,10.7,50.6,26.6,6.65);
                        sdf.stroke(vec4(self.meta_color.rgb,0.24),0.8);
                        let x=mix(18.0,42.0,self.checked);
                        sdf.circle(x,24.8,12.0);sdf.fill(vec4(0.0,0.0,0.0,0.12));
                        sdf.circle(x,24.0,11.7);
                        sdf.fill_keep(vec4(mix(track,vec3(1.0),mix(0.72,0.55,self.dark)),1.0));
                        sdf.stroke(vec4(1.0,1.0,1.0,0.75),0.9);
                        return sdf.result;
                    }
                }
            }
        }
        // An Overlay/Fill hit must follow the Fit row so its height is known.
        hit := ButtonFlatter{width:Fill height:Fill margin:0 text:""}
    }
    mod.spotygram.TogglePreference = mod.spotygram.Preference{row.leading.visible:false row.arrow.visible:false row.toggle.visible:true}
    mod.spotygram.SettingsCard = RoundedView {
        width:Fill height:Fit flow:Down padding:16 spacing:8
        draw_bg +:{color:mix(#xffffff,#x11191d,theme.spotygram_dark) border_color:mix(#xdce5e6,#x29353b,theme.spotygram_dark) border_size:0.7 border_radius:8.0}
    }
    mod.spotygram.SettingsChoice = ButtonFlat {
        width:Fill height:48 margin:0 padding:6 spacing:5 align:Center grab_key_focus:false
        label_walk:Walk{width:Fit height:Fit}
        icon_walk:Walk{width:14 height:14}
        draw_icon +:{color:theme.color_primary svg:crate_resource("self:resources/check.svg")}
        draw_text +:{color:theme.color_text color_hover:theme.color_text color_focus:theme.color_text color_down:theme.color_text text_style:theme.font_bold{font_size:10.5}}
        draw_bg +:{
            active:uniform(0.0) ink:uniform(theme.color_primary) dark:uniform(theme.spotygram_dark) corner_radius:uniform(5.0)
            pixel:fn(){
                let sdf=Sdf2d.viewport(self.pos*self.rect_size);
                sdf.box(0.7,0.7,self.rect_size.x-1.4,self.rect_size.y-1.4,self.corner_radius);
                sdf.fill_keep(vec4(self.ink.rgb,self.active*mix(0.10,0.17,self.dark)+self.hover*0.035+self.down*0.04));
                sdf.stroke(vec4(self.ink.rgb,max(self.focus*0.8,self.active*0.28)),mix(0.7,1.4,self.focus));
                return sdf.result;
            }
        }
        animator +:{open:{
            default:@off
            off:AnimatorState{from:{all:Forward{duration:0.0}} apply:{draw_bg:{active:0.0}}}
            on:AnimatorState{from:{all:Forward{duration:0.0}} apply:{draw_bg:{active:1.0}}}
        }}
    }
    mod.spotygram.SettingsRow = mod.spotygram.Preference {
        row.padding:Inset{top:4 bottom:4}
        row.leading.visible:false
        row.labels.spacing:4
        row.labels.detail.max_lines:6
        hit.draw_bg:mod.spotygram.SettingsChoice.draw_bg{}
        hit.grab_key_focus:false
    }
    mod.spotygram.SettingsToggle = mod.spotygram.SettingsRow {row.arrow.visible:false row.toggle.visible:true}
    mod.spotygram.SettingsAction = mod.spotygram.SettingsRow {
        clip_x:false clip_y:false show_bg:true
        draw_bg:mod.spotygram.Surface.draw_bg{}
        row.min_height:48
        row.padding:12 row.spacing:8
        row.leading.visible:false
        row.leading.width:20 row.leading.height:20
        row.leading.mark.draw_icon.color:theme.color_text
        row.labels.detail.visible:false
        row.labels.title.align:Center
        row.labels.title.draw_text.text_style:theme.font_bold{font_size:10.5}
        row.arrow.visible:false
        hit.draw_bg.corner_radius:12.0
    }
}

#[derive(Script, ScriptHook)]
#[repr(C)]
pub struct DrawNavLens {
    #[deref]
    draw_super: DrawQuad,
    #[live]
    position: f32,
    #[live]
    tint: Vec4,
    #[live]
    vertical: f32,
}

#[derive(Script, ScriptHook, Widget)]
pub struct NavLens {
    #[uid]
    uid: WidgetUid,
    #[source]
    source: ScriptObjectRef,
    #[walk]
    walk: Walk,
    #[redraw]
    #[live]
    draw_bg: DrawNavLens,
    #[rust]
    position: f64,
    #[rust]
    from: f64,
    #[rust]
    target: f64,
    #[rust]
    started: Option<f64>,
    #[rust]
    next_frame: NextFrame,
}

impl NavLens {
    pub fn set_destination(&mut self, cx: &mut Cx, index: usize, color: Vec4) {
        self.draw_bg.tint = color;
        let target = index.min(3) as f64;
        if self.target != target {
            self.from = self.position;
            self.target = target;
            self.started = None;
            self.next_frame = cx.new_next_frame();
        }
        self.draw_bg.redraw(cx);
    }
}

impl Widget for NavLens {
    fn draw_walk(&mut self, cx: &mut Cx2d, _: &mut Scope, walk: Walk) -> DrawStep {
        self.draw_bg.position = self.position as f32;
        self.draw_bg.draw_walk(cx, walk);
        DrawStep::done()
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, _: &mut Scope) {
        if let Some(frame) = self.next_frame.is_event(event) {
            let start = *self.started.get_or_insert(frame.time);
            let time = (frame.time - start).max(0.0);
            if time >= 0.4 {
                self.position = self.target;
            } else {
                let response = (-19.2 * time).exp()
                    * ((13.4 * time).cos() + 19.2 / 13.4 * (13.4 * time).sin());
                self.position = self.target + (self.from - self.target) * response;
                self.next_frame = cx.new_next_frame();
            }
            self.draw_bg.redraw(cx);
        }
    }
}

#[derive(Script, ScriptHook, Widget)]
pub struct Preference {
    #[deref]
    view: View,
}

impl Widget for Preference {
    fn draw_walk(&mut self, cx: &mut Cx2d, scope: &mut Scope, walk: Walk) -> DrawStep {
        self.view.draw_walk(cx, scope, walk)
    }
    fn handle_event(&mut self, cx: &mut Cx, event: &Event, scope: &mut Scope) {
        self.view.handle_event(cx, event, scope);
    }
    fn set_text(&mut self, cx: &mut Cx, text: &str) {
        self.view.label(cx, ids!(title)).set_text(cx, text);
    }
}

pub const STICKERS: &[&[u8]] = &[
    include_bytes!("../resources/emoji_wave.png"),
    include_bytes!("../resources/emoji_peace.png"),
    include_bytes!("../resources/emoji_horns.png"),
    include_bytes!("../resources/emoji_vulcan.png"),
    include_bytes!("../resources/emoji_okay.png"),
    include_bytes!("../resources/emoji_love.png"),
    include_bytes!("../resources/emoji_crossed.png"),
    include_bytes!("../resources/emoji_finger_heart.png"),
    include_bytes!("../resources/emoji_thumbs_up.png"),
    include_bytes!("../resources/emoji_thumbs_down.png"),
    include_bytes!("../resources/emoji_fist.png"),
    include_bytes!("../resources/emoji_fist_bump.png"),
    include_bytes!("../resources/emoji_clap.png"),
    include_bytes!("../resources/emoji_raised_hands.png"),
    include_bytes!("../resources/emoji_open_hands.png"),
    include_bytes!("../resources/emoji_palms_up.png"),
    include_bytes!("../resources/emoji_handshake.png"),
    include_bytes!("../resources/emoji_heart_hands.png"),
    include_bytes!("../resources/emoji_smile.png"),
    include_bytes!("../resources/emoji_relieved.png"),
    include_bytes!("../resources/emoji_sunglasses.png"),
    include_bytes!("../resources/emoji_starstruck.png"),
    include_bytes!("../resources/emoji_grin.png"),
    include_bytes!("../resources/emoji_beaming.png"),
    include_bytes!("../resources/emoji_sweat.png"),
    include_bytes!("../resources/emoji_laugh.png"),
    include_bytes!("../resources/emoji_kiss.png"),
    include_bytes!("../resources/emoji_hearts.png"),
    include_bytes!("../resources/emoji_thinking.png"),
    include_bytes!("../resources/emoji_eyebrow.png"),
    include_bytes!("../resources/emoji_eye_roll.png"),
    include_bytes!("../resources/emoji_sleep.png"),
    include_bytes!("../resources/emoji_unamused.png"),
    include_bytes!("../resources/emoji_pensive.png"),
    include_bytes!("../resources/emoji_cry.png"),
    include_bytes!("../resources/emoji_sob.png"),
    include_bytes!("../resources/emoji_angry.png"),
    include_bytes!("../resources/emoji_steam.png"),
    include_bytes!("../resources/emoji_astonished.png"),
    include_bytes!("../resources/emoji_surprised.png"),
];
