(ns clojure2d.color
  "Color functions.

  This namespace contains color manipulation functions which can be divided into following groups:

  * Color creators
  * Channel manipulations
  * Conversions
  * Palettes / gradients
  * Distances

  ## Representation

  Color can be represented by following types:
  
  * fastmath `Vec4` - this is core type representing 3 color channels and alpha (RGBA). Values are `double` type from `[0-255]` range. [[color]], [[gray]] creators returns `Vec4` representation.
  * fastmath `Vec3` - 3 channels (RGB), assuming `alpha` set to value of `255`.
  * fastmath `Vec2` - gray with alpha
  * `java.awt.Color` - Java AWT representation. Creators are [[awt-color]], [[awt-gray]]. Use [[to-awt-color]] to convert to this representation.
  * `keyword` - one of the defined names (see [[named-colors-list]])
  * `Integer` - packed ARGB value. If value is less than `0xff000000`, alpha is set to `0xff`. Example: `0xffaa01`.
  * `String` - CSS (`#rgb` or `#rgba`) or string containg hexadecimal representation (\"ffaa01\")
  * any `seqable` - list, vector containing 2-4 elements. Conversion is done by applying content to [[color]] function.

  To create color from individual channel values use [[color]] function. To create gray for given intensity call [[gray]].

  By default color is treated as `RGB` with values from ranges `[0.0-255.0]` inclusive.

  All funtions internally convert any color representation to `Vec4` type using [[to-color]] function..
  
  [Coloured list of all names](../static/colors.html)
  
  ## Color/ channel manipulations

  You can access individual channels by calling on of the following:

  * [[red]] or [[ch0]] - to get first channel value.
  * [[green]] or [[ch1]] - to get second channel value.
  * [[blue]] or [[ch2]] - to get third channel value.
  * [[alpha]] - to get alpha value.
  * [[luma]] - to get luma or brightness (range from `0` (black) to `255` (white)).
  * [[hue]] - to get hue value in degrees (range from 0 to 360). Hexagon projection.
  * [[hue-polar]] - to get hue from polar transformation.

  [[set-ch0]], [[set-ch1]], [[set-ch2]] and [[set-alpha]] return new color with respective channel set to new value.

  General [[set-channel]] and [[get-channel]] can work with any colorspace.

  To make color darker/brighter use [[darken]] / [[lighten]] functions. Operations are done in `Lab` color space.

  To change saturation call [[saturate]] / [[desaturate]]. Operations are done in `LCH` color space.

  You can also [[modulate]] color (ie. multiply by given value).
  
  ## Conversions

  Color can be converted from RGB to other color space (and back). List of color spaces are listed under [[colorspaces-list]] variable. There are two types of conversions:

  * raw - with names `to-XXX` and `from-XXX` where `XXX` is color space name. Every color space has it's own value range for each channel. `(comp from-XXX to-XXX)` acts almost as identity.
  * normalized - with names `to-XXX*` and `from-XXX*` where `XXX` is color space name. `to-XXX*` returns values normalized to `[0-255]` range. `from-XXX*` expects also channel values in range `[0-255]`.

  NOTE: there is no information which color space is used. It's just a matter of your interpretation.

  NOTE 2: be aware that converting function do not clamp any values to/from expected range.

  Color space conversion functions are collected in two maps [[colorspaces]] for raw and [[colorspaces*]] for normalized functions. Keys are color space names as `keyword` and values are vectors with `to-` fn as first and `from-` fn as second element.
    
  ## Palettes / gradients

  ### Links

  List of all defined colors and palettes:
  
  * [Named palettes](../static/palettes/index.html)
  * [Gradients](../static/gradients/index.html)
  
  ### Palette

  Palette is just sequence of colors.

  There are plenty of them predefined or can be generated:

  * [[palette]] to access palette by keyword (from presets), by number (from colourlovers). Use [[palette]] to resample palette or convert gradient to palette.
  * [[paletton-palette]] function to generate palette of type: `:monochromatic`, `:triad`, `:tetrad` with complementary color for given hue and configuration. See also [Paletton](http://paletton.com) website for details.

  Call [[palette]] without any parameters to get list of predefined gradients.
  
  ### Gradient

  Gradient is continuous functions which accepts value from `[0-1]` range and returns color. Call [[gradient]] to create one.

  Call [[gradient]] without any parameters to obtain list of predefined gradients.
  
  Use [[gradient]] to convert any palette to gradient or access predefined gradients by keyword.
  
  ### Conversions

  To convert palette to gradient call [[gradient]] function. You can set interpolation method and colorspace.
  To convert gradient to palette call [[palette]] function.

  Call [[palette]] to resample palette to other number of colors. Internally input palette is converted to gradient and sampled back.

  Use [[lerp]], [[lerp+]], [[mix]], [[average]], [[mixbox]] to mix two colors in different ways.
  
  ## Distances

  Several functions to calculate difference between colors, `delta-E*-xxx` etc.

  ## References

  * https://vis4.net/chromajs/
  * https://github.com/thi-ng/color
  * https://github.com/nschloe/colorio"
  {:metadoc/categories {:ops "Color/channel operations"
                        :conv "Color conversions"
                        :gr "Gradients"
                        :pal "Colors, palettes"
                        :interp "Interpolation"
                        :dist "Distance"}}
  (:require [fastmath.core :as m]
            [fastmath.random :as r]
            [fastmath.vector :as v]
            [fastmath.stats :as stat]
            [fastmath.interpolation :as i]
            [fastmath.interpolation.linear :as li]
            [fastmath.ml.clustering :as cl]
            [fastmath.easings :as e]
            [clojure2d.protocols :as pr]
            [clojure2d.color.whitepoints :as wp]
            [clojure2d.color.rgb :as rgb]
            [clojure.java.io :refer [input-stream resource]]
            [clojure.edn :as edn]
            [fastmath.matrix :as mat])
  (:import [fastmath.vector Vec2 Vec3 Vec4]
           [java.awt Color]
           [clojure.lang APersistentVector ISeq Seqable]
           [com.scrtwpns Mixbox]))

(set! *unchecked-math* :warn-on-boxed)
(m/use-primitive-operators)

(defonce ^:private meta-conv #{:conv})
(defonce ^:private meta-ops #{:ops})

;; load the stuff to delay

;;;;;;;;;
;; read
;;;;;;;;;

(defn- read-edn [n] (-> (resource n) input-stream slurp edn/read-string))

;; color names

(defonce ^:private color-presets-delay
  (delay (into {} (map (fn [[k v]]
                         [k (pr/to-color v)]) (read-edn "color_presets.edn")))))

(defn named-colors-list
  "Return list of the named colors."
  {:metadoc/categories #{:pal}}
  [] (keys @color-presets-delay))

;; palettes / gradients

(defn- load-edn-file-
  [prefix n]
  (read-edn (str prefix "c2d_" n ".edn")))

(defonce ^:private load-edn-file (memoize load-edn-file-))

(defn- get-palette-or-gradient
  [prefix k]
  (let [nm (cond
             (keyword? k) (namespace k)
             (integer? k) "colourlovers"
             :else k)
        p (load-edn-file prefix nm)]
    (p k)))

;; ## Clamping functions

;; First define some clamping functions

(defn- clamp255
  "Constrain value `a` to 0-255 double.

  Use to ensure that value is in RGB range.
  Accepts and returns `double`.

  See also [[lclamp255]], [[clamp]] and [[lclamp]]."
  {:metadoc/categories meta-ops}
  ^double [^double a]
  (m/constrain a 0 255))

(defn- lclamp255
  "Constrain value `a` to 0-255 long (rounding if necessary).

  Use to ensure that value is in RGB range.

  See also [[clamp255]], [[clamp]] and [[lclamp]]."
  {:metadoc/categories meta-ops}
  ^long [^double a]
  (m/constrain (m/round a) 0 255))

(defn possible-color?
  "Check if given argument can be considered as color.

  Check is done by analyzing type of the argument.
  
  See also [[valid-color?]]."
  {:metadoc/categories #{:pal}}
  [c]
  (or (string? c)
      (and (not (seqable? c))
           (satisfies? pr/ColorProto c))
      (and (seqable? c)
           (let [v (first c)]
             (and (number? v)
                  (< (long v) 0x01000000))))))

(defn possible-palette?
  "Check if given argument can be considered as palette.

  Check is done by analyzing type of the argument."
  {:metadoc/categories #{:pal}}
  [c]
  (and (seqable? c)
       (not (possible-color? c))
       (not (fn? c))))

(defn valid-color?
  "Check if given argument is valid color.

  Check is done by trying to convert to color representation.
  
  Returns color when valid.

  See also [[possible-color?]]"
  {:metadoc/categories #{:pal}}
  [c]
  (try
    (pr/to-color c)
    (catch Exception _ false)))

;; ## Color representation

(defn to-color
  "Convert any color representation to `Vec4` vector."
  {:metadoc/categories meta-ops}
  ^Vec4 [c] (pr/to-color c))

(defn to-awt-color
  "Convert any color representation to `java.awt.Color`."
  {:metadoc/categories meta-ops}
  ^java.awt.Color [c] (pr/to-awt-color c))

(defn luma
  "Returns luma"
  {:metadoc/categories meta-ops}
  ^double [c] (pr/luma c))

(defn- luma-fn
  "Local luma conversion function"
  ^double [^double r ^double g ^double b]
  (+ (* 0.212671 r)
     (* 0.715160 g)
     (* 0.072169 b)))

(declare from-sRGB)

(defn relative-luma
  "Returns relative luminance"
  {:metadoc/categories meta-ops}
  ^double [c]
  (let [^Vec4 c (from-sRGB c)]
    (luma-fn (.x c) (.y c) (.z c))))

(defn red
  "Returns red (first channel) value."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/red c))

(defn green
  "Returns green (second channel) value."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/green c))

(defn blue
  "Returns blue (third channel) value."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/blue c))

(defn alpha
  "Returns alpha value."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/alpha c))

(defn ch0
  "Returns first channel value. Same as [[red]]."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/red c))

(defn ch1
  "Returns second channel value. Same as [[green]]."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/green c))

(defn ch2
  "Returns third channel value. Same as [[blue]]."
  {:metadoc/categories meta-ops}
  ^double [c] (pr/blue c))

(defn hue-polar
  "Hue value of color (any representation). Returns angle (0-360).
  
  Uses polar transformation. See also [[hue]]."
  {:metadoc/categories meta-ops}
  ^double [c]
  (let [^Vec4 c (pr/to-color c)
        a (* 0.5 (- (+ (.x c) (.x c)) (.y c) (.z c)))
        b (* 0.8660254037844386 (- (.y c) (.z c)))
        h (m/degrees (m/atan2 b a))]
    (if (neg? h) (+ 360.0 h) h)))

(declare to-HC)

(defn hue
  "Hue value of color (any representation). Returns angle (0-360).
  
  Uses hexagonal transformation. See also [[hue-polar]]."
  {:metadoc/categories meta-ops}
  ^double [c]
  (let [^Vec4 ret (to-HC (pr/to-color c))] (.x ret)))

(defn set-alpha
  "Set alpha channel and return new color"
  {:metadoc/categories meta-ops}
  ^Vec4 [c a]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. (.x v) (.y v) (.z v) a)))

(defn set-red
  "Set red channel and return new color."
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. val (.y v) (.z v) (.w v))))

(defn set-green
  "Set green channel and return new color."
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. (.x v) val (.z v) (.w v))))

(defn set-blue
  "Set blue channel and return new color"
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. (.x v) (.y v) val (.w v))))

(defn set-ch0
  "Set red channel and return new color."
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. val (.y v) (.z v) (.w v))))

(defn set-ch1
  "Set green channel and return new color."
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. (.x v) val (.z v) (.w v))))

(defn set-ch2
  "Set blue channel and return new color"
  {:metadoc/categories meta-ops}
  ^Vec4 [c val]
  (let [^Vec4 v (pr/to-color c)]
    (Vec4. (.x v) (.y v) val (.w v))))

(defn set-awt-alpha
  "Set alpha channel and return `Color` representation."
  {:metadoc/categories meta-ops}
  ^Color [c a]
  (let [^Color cc (pr/to-awt-color c)]
    (Color. (.getRed cc)
            (.getGreen cc)
            (.getBlue cc)
            (lclamp255 a))))

(defn awt-color
  "Create java.awt.Color object.

  See also [[color]], [[gray]]."
  {:metadoc/categories meta-ops}
  (^Color [c]
   (pr/to-awt-color c))
  (^Color [c a]
   (set-awt-alpha c a))
  (^Color [^double r ^double g ^double b]
   (Color. (lclamp255 r)
           (lclamp255 g)
           (lclamp255 b)))
  (^Color [^double r ^double g ^double b ^double a]
   (Color. (lclamp255 r)
           (lclamp255 g)
           (lclamp255 b)
           (lclamp255 a))))

(defn color
  "Create Vec4 object as color representation.

  Arity: 

  * 1 - convert to `Vec4` from any color. Same as [[to-color]]
  * 2 - sets color alpha
  * 3 - sets r,g,b with alpha 255
  * 4 - sets r,g,b and alpha
  
  See also [[gray]]. [[awt-color]], [[awt-gray]]."
  {:metadoc/categories meta-ops}
  (^Vec4 [c]
   (pr/to-color c))
  (^Vec4 [c a]
   (set-alpha c a))
  (^Vec4 [^double r ^double g ^double b]
   (Vec4. (clamp255 r)
          (clamp255 g)
          (clamp255 b)
          255.0))
  (^Vec4 [^double r ^double g ^double b ^double a]
   (Vec4. (clamp255 r)
          (clamp255 g)
          (clamp255 b)
          (clamp255 a))))

(defn clamp
  "Clamp all color channels to `[0-255]` range."
  {:metadoc/categories meta-ops}
  [c]
  ^Vec4 (v/fmap (pr/to-color c) clamp255))

(defn lclamp
  "Clamp all color channels to `[0-255]` range. Round if necessary."
  {:metadoc/categories meta-ops}
  ^Vec4 [c]
  (v/fmap (pr/to-color c) lclamp255))

(defn scale
  "Multiply color channels by given value, do not change alpha channel by default"
  {:metadoc/categories meta-ops}
  (^Vec4 [c ^double v] (scale c v false))
  (^Vec4 [c ^double v alpha?]
   (let [^Vec4 c (pr/to-color c)]
     (Vec4. (* v (.x c))
            (* v (.y c))
            (* v (.z c))
            (if alpha? (* v (.w c)) (.w c))))))

(defn scale-down
  "Divide color channels by 255.0"
  {:metadoc/categories meta-ops}
  (^Vec4 [c] (scale-down c false))
  (^Vec4 [c alpha?] (scale c (/ 255.0) alpha?)))

(defn scale-up
  "Multiply color channels by 255.0"
  {:metadoc/categories meta-ops}
  (^Vec4 [c] (scale-up c false))
  (^Vec4 [c alpha?] (scale c 255.0 alpha?)))

(defn gray
  "Create grayscale color based on intensity `v`. Optional parameter alpha `a`.

  See also [[color]]"
  {:metadoc/categories meta-ops}
  (^Vec4 [^double v] (color v v v))
  (^Vec4 [^double v ^double a] (color v v v a)))

(defn awt-gray
  "Create grayscale color based on intensity `v`. Optional parameter alpha `a`.

  AWT version of [[gray]]. See also [[awt-color]]"
  {:metadoc/categories meta-ops}
  (^Color [^double v] (awt-color v v v))
  (^Color [^double v ^double a] (awt-color v v v a)))

(defn- string->long
  "Parse color string and convert to long"
  [^String s]
  (let [^String s (if (= (first s) \#) (subs s 1) s)
        cs (count s)]
    (if (and (= cs 8)
             (= (subs s 6) "00"))
      (set-alpha (pr/to-color (Long/parseLong (subs s 0 6) 16)) 0)
      (Long/parseLong (condp = cs
                        1 (str s s s s s s)
                        2 (str s s s)
                        3 (let [[r g b] s] (str r r g g b b))
                        8 (str (subs s 6) (subs s 0 6))
                        s) 16))))

(extend-protocol pr/ColorProto
  Vec2
  (to-color ^Vec4 [^Vec2 c]
    (Vec4. (.x c) (.x c) (.x c) (.y c)))
  (to-awt-color ^Color [^Vec2 c]
    (let [v (lclamp255 (.x c))]      
      (Color. v v v (lclamp255 (.y c)))))
  (luma ^double [^Vec2 c] (.x c))
  (red ^double [^Vec2 c] (.x c))
  (green ^double [^Vec2 c] (.x c))
  (blue ^double [^Vec2 c] (.x c))
  (alpha ^double [^Vec2 c] (.y c))
  Vec3
  (to-color ^Vec4 [^Vec3 c]
    (Vec4. (.x c) (.y c) (.z c) 255))
  (to-awt-color ^Color [^Vec3 c]
    (Color. (lclamp255 (.x c))
            (lclamp255 (.y c))
            (lclamp255 (.z c))))
  (luma ^double [^Vec3 c] (luma-fn (.x c) (.y c) (.z c)))
  (red ^double [^Vec3 c] (.x c))
  (green ^double [^Vec3 c] (.y c))
  (blue ^double [^Vec3 c] (.z c))
  (alpha ^double [_] 255.0)
  Vec4
  (to-color ^Vec4 [c] c)
  (to-awt-color ^Color [^Vec4 c]
    (Color.  (lclamp255 (.x c))
             (lclamp255 (.y c))
             (lclamp255 (.z c))
             (lclamp255 (.w c))))
  (luma ^double [^Vec4 c] (luma-fn (.x c) (.y c) (.z c)))
  (red ^double [^Vec4 c] (.x c))
  (green ^double [^Vec4 c] (.y c))
  (blue ^double [^Vec4 c] (.z c))
  (alpha ^double [^Vec4 c] (.w c))
  clojure.lang.Keyword
  (to-color ^Vec4 [n] (@color-presets-delay n))
  (to-awt-color ^Color [n] (to-awt-color (@color-presets-delay n)))
  (luma ^double [n] (pr/luma (@color-presets-delay n)))
  (red ^double [n] (pr/red (@color-presets-delay n)))
  (green ^double [n] (pr/green (@color-presets-delay n)))
  (blue ^double [n] (pr/blue (@color-presets-delay n)))
  (alpha ^double [n] (pr/alpha (@color-presets-delay n)))
  Color
  (to-color ^Vec4 [^Color c]
    (Vec4. (.getRed c)
           (.getGreen c)
           (.getBlue c)
           (.getAlpha c)))
  (to-awt-color ^Color [c] c)
  (luma ^double [^Color c] (luma-fn (.getRed c) (.getGreen c) (.getBlue c)))
  (red ^double [^Color c] (.getRed c))
  (green ^double [^Color c] (.getGreen c))
  (blue ^double [^Color c] (.getBlue c))
  (alpha ^double [^Color c] (.getAlpha c))
  nil
  (to-color [_] nil)
  (to-awt-color [_] nil)
  (alpha [_] nil)
  (red [_] nil)
  (green [_] nil)
  (blue [_] nil)
  Long
  (alpha ^double [^long c] (bit-and 0xff (m/>> c 24)))
  (red ^double [^long c] (bit-and 0xff (m/>> c 16)))
  (green ^double [^long c] (bit-and 0xff (m/>> c 8)))
  (blue ^double [^long c] (bit-and 0xff c))
  (to-color ^Vec4 [^long c] (Vec4. (bit-and 0xff (m/>> c 16))
                                   (bit-and 0xff (m/>> c 8))
                                   (bit-and 0xff c)
                                   (if (zero? (bit-and 0xff000000 c)) 255.0 (bit-and 0xff (m/>> c 24)))))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma ^double [c] (pr/luma (pr/to-color c)))
  Integer
  (alpha ^double [c] (bit-and 0xff (m/>> ^int c 24)))
  (red ^double [c] (bit-and 0xff (m/>> ^int c 16)))
  (green ^double [c] (bit-and 0xff (m/>> ^int c 8)))
  (blue ^double [c] (bit-and 0xff ^int c))
  (to-color ^Vec4 [c] (Vec4. (bit-and 0xff (m/>> ^int c 16))
                             (bit-and 0xff (m/>> ^int c 8))
                             (bit-and 0xff ^int c)
                             (if (zero? (bit-and 0xff000000 c)) 255.0 (bit-and 0xff (m/>> ^int c 24)))))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma ^double [c] (pr/luma (pr/to-color c)))
  String
  (alpha ^double [^String c] (pr/alpha (string->long c)))
  (red ^double [^String c] (pr/red (string->long c)))
  (green ^double [^String c] (pr/green (string->long c)))
  (blue ^double [^String c] (pr/blue (string->long c)))
  (to-color ^Vec4 [^String c] (pr/to-color (string->long c)))
  (to-awt-color ^Color [^String c] (pr/to-awt-color (pr/to-color (string->long c))))
  (luma ^double [^String c] (pr/luma (pr/to-color (string->long c))))
  APersistentVector
  (to-color ^Vec4 [c] (case (count c)
                        0 (Vec4. 0.0 0.0 0.0 255.0)
                        1 (gray (c 0))
                        2 (gray (c 0) (c 1))
                        3 (Vec4. (c 0) (c 1) (c 2) 255.0)
                        (Vec4. (c 0) (c 1) (c 2) (c 3))))
  (alpha ^double [c] (pr/alpha (pr/to-color c)))
  (red ^double [c] (pr/red (pr/to-color c)))
  (green ^double [c] (pr/green (pr/to-color c)))
  (blue ^double [c] (pr/blue (pr/to-color c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma ^double [c] (pr/luma (pr/to-color c)))
  ISeq
  (to-color ^Vec4 [c] (case (count (take 4 c))
                        0 (Vec4. 0.0 0.0 0.0 255.0)
                        1 (gray (first c))
                        2 (gray (first c) (second c))
                        3 (Vec4. (first c) (second c) (nth c 2) 255.0)
                        (Vec4. (first c) (second c) (nth c 2) (nth c 3 255.0))))
  (alpha ^double [c] (pr/alpha (pr/to-color c)))
  (red ^double [c] (pr/red (pr/to-color c)))
  (green ^double [c] (pr/green (pr/to-color c)))
  (blue ^double [c] (pr/blue (pr/to-color c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma [c] ^double (pr/luma (pr/to-color c)))
  Seqable
  (to-color ^Vec4 [c] (pr/to-color (seq c)))
  (alpha ^double [c] (pr/alpha (seq c)))
  (red ^double [c] (pr/red (seq c)))
  (green ^double [c] (pr/green (seq c)))
  (blue ^double [c] (pr/blue (seq c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (seq c)))
  (luma [c] ^double (pr/luma (seq c))))

(extend-type (Class/forName "[D")
  pr/ColorProto
  (to-color ^Vec4 [c] (case (alength ^doubles c)
                        0 (Vec4. 0.0 0.0 0.0 255.0)
                        1 (gray (aget ^doubles c 0))
                        2 (gray (aget ^doubles c 0) (aget ^doubles c 1))
                        3 (Vec4. (aget ^doubles c 0) (aget ^doubles c 1) (aget ^doubles c 2) 255.0)
                        (Vec4. (aget ^doubles c 0) (aget ^doubles c 1) (aget ^doubles c 2) (aget ^doubles c 3))))
  (alpha ^double [c] (pr/alpha (pr/to-color c)))
  (red ^double [c] (pr/red (pr/to-color c)))
  (green ^double [c] (pr/green (pr/to-color c)))
  (blue ^double [c] (pr/blue (pr/to-color c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma [c] ^double (pr/luma (pr/to-color c))))

(extend-type (Class/forName "[I")
  pr/ColorProto
  (to-color ^Vec4 [c] (case (alength ^ints c)
                        0 (Vec4. 0.0 0.0 0.0 255.0)
                        1 (gray (aget ^ints c 0))
                        2 (gray (aget ^ints c 0) (aget ^ints c 1))
                        3 (Vec4. (aget ^ints c 0) (aget ^ints c 1) (aget ^ints c 2) 255.0)
                        (Vec4. (aget ^ints c 0) (aget ^ints c 1) (aget ^ints c 2) (aget ^ints c 3))))
  (alpha ^double [c] (pr/alpha (pr/to-color c)))
  (red ^double [c] (pr/red (pr/to-color c)))
  (green ^double [c] (pr/green (pr/to-color c)))
  (blue ^double [c] (pr/blue (pr/to-color c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma [c] ^double (pr/luma (pr/to-color c))))

(extend-type (Class/forName "[J")
  pr/ColorProto
  (to-color ^Vec4 [c] (case (alength ^longs c)
                        0 (Vec4. 0.0 0.0 0.0 255.0)
                        1 (gray (aget ^longs c 0))
                        2 (gray (aget ^longs c 0) (aget ^longs c 1))
                        3 (Vec4. (aget ^longs c 0) (aget ^longs c 1) (aget ^longs c 2) 255.0)
                        (Vec4. (aget ^longs c 0) (aget ^longs c 1) (aget ^longs c 2) (aget ^longs c 3))))
  (alpha ^double [c] (pr/alpha (pr/to-color c)))
  (red ^double [c] (pr/red (pr/to-color c)))
  (green ^double [c] (pr/green (pr/to-color c)))
  (blue ^double [c] (pr/blue (pr/to-color c)))
  (to-awt-color ^Color [c] (pr/to-awt-color (pr/to-color c)))
  (luma [c] ^double (pr/luma (pr/to-color c))))

;;

(defn format-hex
  "Convert color to hex string (css).

  When alpha is lower than 255.0, #rgba is returned."
  {:metadoc/categories meta-ops}
  ^String [c]
  (let [^Vec4 c (pr/to-color c)
        s (str "#" (format "%02x" (lclamp255 (.x c)))
               (format "%02x" (lclamp255 (.y c)))
               (format "%02x" (lclamp255 (.z c))))
        a (lclamp255 (.w c))]
    (if (< a 255) (str s (format "%02x" a)) s)))

(defn pack
  "Pack color to ARGB 32bit integer."
  {:metadoc/categories meta-ops}
  [c]
  (unchecked-int (bit-or (m/<< (lclamp255 (pr/alpha c)) 24)
                         (m/<< (lclamp255 (pr/red c)) 16)
                         (m/<< (lclamp255 (pr/green c)) 8)
                         (lclamp255 (pr/blue c)))))

(def ^{:doc "Convert color to `quil` color type (ie. ARGB Integer). Alias to [[pack]]."
       :metadoc/categories meta-ops} quil pack)

(defn black?
  "Check if color is black"
  [c]
  (let [^Vec4 v (clamp c)]
    (and (zero? (.x v))
         (zero? (.y v))
         (zero? (.z v)))))

(defn not-black?
  "Check if color is not black"
  [c]
  (let [^Vec4 v (clamp c)]
    (or (pos? (.x v))
        (pos? (.y v))
        (pos? (.z v)))))

;; ## Colorspace functions
;;
;; Conversion from RGB to specific color space always converts to range 0-255
;; Reverse conversion is not normalized and can exceed 0-255 range


;; ### CMY

(defn to-CMY
  "RGB -> CMY"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (Vec4. (- 255.0 (.x c))
           (- 255.0 (.y c))
           (- 255.0 (.z c))
           (.w c))))

(def ^{:doc "CMY -> RGB" :metadoc/categories meta-conv} from-CMY to-CMY)
(def ^{:doc "CMY -> RGB, alias for [[to-CMY]]" :metadoc/categories meta-conv} to-CMY* to-CMY)
(def ^{:doc "CMY -> RGB, alias for [[from-CMY]]" :metadoc/categories meta-conv} from-CMY* to-CMY)

;; ### OHTA

(defn to-OHTA
  "sRGB -> OHTA

  Returned ranges:

  * I1: 0.0 - 255.0
  * I2: -127.5 - 127.5
  * I3: -127.5 - 127.5"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb] 
  (let [^Vec4 c (pr/to-color srgb)
        i1 (/ (+ (.x c) (.y c) (.z c)) 3.0)
        i2 (* 0.5 (- (.x c) (.z c)))
        i3 (* 0.25 (- (* 2.0 (.y c)) (.x c) (.z c)))]
    (Vec4. i1 i2 i3 (.w c))))

(def ^:private ^:const ohta-s (Vec4. 0.0 127.5 127.5 0.0))

(defn to-OHTA*
  "RGB -> OHTA, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (v/add (to-OHTA srgb) ohta-s))

(def ^{:private true :const true :tag 'double} c23- (/ -2.0 3.0))
(def ^{:private true :const true :tag 'double} c43 (/ 4.0 3.0))

(defn from-OHTA
  "OHTA -> RGB

  For ranges, see [[to-OHTA]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [ohta]
  (let [^Vec4 c (pr/to-color ohta)
        i1 (.x c)
        i2 (.y c)
        i3 (.z c)
        r (+ i1 i2 (* c23- i3))
        g (+ i1 (* c43 i3))
        b (+ i1 (- i2) (* c23- i3))]
    (Vec4. r g b (.w c))))

(defn from-OHTA*
  "OHTA -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [ohta*]
  (from-OHTA (v/sub (pr/to-color ohta*) ohta-s)))

;; ### sRGB

(defn to-sRGB
  "linear RGB -> sRGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [linear-rgb]
  (let [^Vec4 c (pr/to-color linear-rgb)]
    (v/vec4 (-> (Vec3. (.x c) (.y c) (.z c))
                (v/div 255.0)
                (v/fmap rgb/linear-to-srgb)
                (v/mult 255.0))
            (.w c))))

(defn from-sRGB
  "sRGB -> linear RGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [^Vec4 c (pr/to-color srgb)]
    (v/vec4 (-> (Vec3. (.x c) (.y c) (.z c))
                (v/div 255.0)
                (v/fmap rgb/srgb-to-linear)
                (v/mult 255.0))
            (.w c))))

(defn RGB-to-RGB1
  ^Vec4 [rgb]
  (let [^Vec4 c (pr/to-color rgb)]
    (Vec4. (/ (.x c) 255.0)
           (/ (.y c) 255.0)
           (/ (.z c) 255.0)
           (.w c))))

(defn RGB1-to-RGB
  ^Vec4 [rgb1]
  (let [^Vec4 c (pr/to-color rgb1)]
    (Vec4. (* (.x c) 255.0)
           (* (.y c) 255.0)
           (* (.z c) 255.0)
           (.w c))))

(def ^{:doc "linear RGB -> sRGB" :metadoc/categories meta-conv} to-sRGB* to-sRGB)
(def ^{:doc "sRGB -> linear RGB" :metadoc/categories meta-conv} from-sRGB* from-sRGB)

;; ### Oklab

(defn- linear->Oklab
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        l (m/cbrt (+ (* 0.4122214708 (.x c)) (* 0.5363325363 (.y c)) (* 0.0514459929 (.z c))))
        m (m/cbrt (+ (* 0.2119034982 (.x c)) (* 0.6806995451 (.y c)) (* 0.1073969566 (.z c))))
        s (m/cbrt (+ (* 0.0883024619 (.x c)) (* 0.2817188376 (.y c)) (* 0.6299787005 (.z c))))]
    (Vec4. (+ (* 0.2104542553 l) (* 0.7936177850 m) (* -0.0040720468 s))
           (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
           (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))
           (.w c))))

(defn to-Oklab
  "sRGB -> Oklab

  https://bottosson.github.io/posts/oklab/

  * L: 0.0 - 1.0
  * a: -0.234 - 0.276
  * b: -0.312 - 0.199"
  ^Vec4 [srgb]
  (linear->Oklab (scale (from-sRGB srgb) 0.00392156862745098)))

(defn to-Oklab*
  "sRGB -> Oklab, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Oklab srgb)]
    (Vec4. (m/mnorm (.x c) 0.0 0.9999999934735462 0.0 255.0)
           (m/mnorm (.y c) -0.23388757418790818 0.27621675349252356 0.0 255.0)
           (m/mnorm (.z c) -0.3115281476783752  0.19856975465179516 0.0 255.0)
           (.w c))))

(defn- Oklab->linear
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        l (m/cb (+ (.x c) (* 0.3963377774 (.y c)) (* 0.2158037573 (.z c))))
        m (m/cb (+ (.x c) (* -0.1055613458 (.y c)) (* -0.0638541728 (.z c))))
        s (m/cb (+ (.x c) (* -0.0894841775 (.y c)) (* -1.2914855480 (.z c))))]
    (Vec4. (+ (* 4.0767416621  l) (* -3.3077115913 m) (* 0.2309699292 s))
           (+ (* -1.2684380046 l) (* 2.6097574011 m) (* -0.3413193965 s))
           (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s))
           (.w c))))

(defn from-Oklab
  "Oklab -> sRGB, see [[to-Oklab]]"
  ^Vec4 [oklab]
  (to-sRGB (scale (Oklab->linear oklab) 255.0)))

(defn from-Oklab*
  "Oklab -> sRGB, normalized"
  ^Vec4 [oklab*]
  (let [^Vec4 c (pr/to-color oklab*)]
    (from-Oklab (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.9999999934735462)
                       (m/mnorm (.y c) 0.0 255.0 -0.23388757418790818 0.27621675349252356)
                       (m/mnorm (.z c) 0.0 255.0 -0.3115281476783752  0.19856975465179516)
                       (.w c)))))

;;

(defn to-luma-color-hue
  "For given color space return polar representation of the color"
  (^Vec4 [to c] (to-luma-color-hue (to c)))
  (^Vec4 [c]
   (let [^Vec4 cc (pr/to-color c)
         H (m/atan2 (.z cc) (.y cc))
         Hd (m/degrees (if (neg? H) (+ H m/TWO_PI) H))
         C (m/hypot-sqrt (.y cc) (.z cc))]
     (Vec4. (.x cc) C Hd (.w cc)))))

(defn from-luma-color-hue
  "For given color space convert from polar representation of the color"
  (^Vec4 [from c] (from (from-luma-color-hue c)))
  (^Vec4 [c]
   (let [^Vec4 c (pr/to-color c)
         h (m/radians (.z c))]
     (Vec4. (.x c) (* (.y c) (m/cos h)) (* (.y c) (m/sin h)) (.w c)))))

;;

(defn to-Oklch
  "sRGB -> Oklch"
  ^Vec4 [srgb]
  (to-luma-color-hue to-Oklab srgb))

(defn to-Oklch*
  "sRGB -> Oklch, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Oklch srgb)]
    (Vec4. (m/mnorm (.x c) 0.0 0.9999999934735462 0.0 255.0)
           (m/mnorm (.y c) 0.0 0.32249096477516426 0.0 255.0)
           (m/mnorm (.z c) 0.0 359.99988541074447 0.0 255.0)
           (.w c))))

(defn from-Oklch
  "Oklch -> sRGB"
  ^Vec4 [oklch]
  (from-luma-color-hue from-Oklab oklch))

(defn from-Oklch*
  "Oklch -> sRGB, normalized"
  ^Vec4 [oklch*]
  (let [^Vec4 c (pr/to-color oklch*)]
    (from-Oklch (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.9999999934735462)
                       (m/mnorm (.y c) 0.0 255.0 0.0 0.32249096477516426)
                       (m/mnorm (.z c) 0.0 255.0 0.0 359.99988541074447)
                       (.w c)))))

;; Okhsv, Okhsl, Okhwb
;; https://bottosson.github.io/posts/colorpicker/

(defn- toe
  ^double [^double x]
  (let [v (- (* 1.170873786407767 x) 0.206)]
    (* 0.5 (+ v (m/sqrt (+ (m/sq v) (* 0.14050485436893204 x)))))))

(defn- inv-toe
  ^double [^double x]
  (/ (+ (* x x) (* 0.206 x))
     (* 1.17087378640776 (+ x 0.03))))

(defn- ->ST
  ^Vec2 [^Vec2 cusp]
  (Vec2. (/ (.y cusp) (.x cusp))
         (/ (.y cusp) (- 1.0 (.x cusp)))))

(defmacro ^:private oklab-poly
  [k0 k1 k2 k3 k4 wl wm ws]
  `(Vec4. (+ ~k0 (* ~k1 ~'a) (* ~k2 ~'b) (* ~k3 ~'a ~'a) (* ~k4 ~'a ~'b)) ~wl ~wm ~ws))

(defn- compute-max-saturation
  ^double [^double a ^double b]
  (let [^Vec4 v (cond
                  (> (- (* -1.88170328 a)
                        (* 0.80936493 b)) 1.0)
                  (oklab-poly 1.19086277 1.76576728 0.59662641 0.75515197 0.56771245
                              4.0767416621 -3.3077115913 0.2309699292)

                  (> (- (* 1.81444104 a)
                        (* 1.19445276 b)) 1.0)
                  (oklab-poly 0.73956515 -0.45954404 0.08285427 0.12541070 0.14503204
                              -1.2684380046 2.6097574011 -0.3413193965)

                  :else (oklab-poly 1.35733652 -0.00915799 -1.15130210 -0.50559606 0.00692167
		                    -0.0041960863 -0.7034186147 1.7076147010))
        S (.x v)
        wl (.y v)
        wm (.z v)
        ws (.w v)
        kl (+ (* 0.3963377774 a) (* 0.2158037573 b))
        km (+ (* -0.1055613458 a) (* -0.0638541728 b))
        ks (+ (* -0.0894841775 a) (* -1.2914855480 b))
        l- (inc (* S kl))
        m- (inc (* S km))
        s- (inc (* S ks))
        l (* l- l- l-)
        m (* m- m- m-)
        s (* s- s- s-)
        lds (* 3.0 kl l- l-)
        mds (* 3.0 km m- m-)
        sds (* 3.0 ks s- s-)
        lds2 (* 6.0 kl kl l-)
        mds2 (* 6.0 km km m-)
        sds2 (* 6.0 ks ks s-)
        f (+ (* wl l) (* wm m) (* ws s))
        f1 (+ (* wl lds) (* wm mds) (* ws sds))
        f2 (+ (* wl lds2) (* wm mds2) (* ws sds2))]
    (- S (/ (* f f1)
            (- (* f1 f1) (* 0.5 f f2))))))

(defn- find-cusp
  ^Vec2 [^double a ^double b]
  (let [S-cusp (compute-max-saturation a b)
        ^Vec4 rgb-at-max (Oklab->linear (Vec4. 1.0 (* S-cusp a) (* S-cusp b) 1.0))
        L-cusp (m/cbrt (/ (max (.x rgb-at-max) (.y rgb-at-max) (.z rgb-at-max))))]
    (Vec2. L-cusp (* L-cusp S-cusp))))

(defn to-Okhsv
  "sRGB -> Okhsv

  Ranges:

  * h: 0.0 - 1.0
  * s: 0.0 - 1.0
  * v: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [^Vec4 lab (to-Oklab srgb)]
    (if (zero? (.x lab))
      (Vec4. 0.0 0.0 0.0 (.w lab))
      (let [L (.x lab)
            C (m/hypot-sqrt (.y lab) (.z lab))
            a- (/ (.y lab) C)
            b- (/ (.z lab) C)
            h (+ 0.5 (/ (* 0.5 (m/atan2 (- (.z lab)) (- (.y lab)))) m/PI))
            ^Vec2 ST-max (->ST (find-cusp a- b-))
            S-max (.x ST-max)
            T-max (.y ST-max)
            k (- 1.0 (/ 0.5 S-max))
            t (/ T-max (+ C (* L T-max)))
            Lv (* t L)
            Cv (* t C)
            Lvt (inv-toe Lv)
            Cvt (/ (* Cv Lvt) Lv)
            ^Vec4 rgb-scale (Oklab->linear (Vec4. Lvt (* a- Cvt) (* b- Cvt) 1.0))
            scale-L (m/cbrt (/ (max (.x rgb-scale) (.y rgb-scale) (.z rgb-scale) 0.0)))
            L (/ L scale-L)
            #_#_ C (/ C scale-L) ;; it's in original source code
            tL (toe L)
            #_#_ C (/ (* C tL) L) 
            L tL
            v (/ L Lv)
            s (* (+ 0.5 T-max)
                 (/ Cv (+ (* 0.5 T-max)
                          (* T-max k Cv))))]
        (Vec4. h s v (.w lab))))))

(defn to-Okhsv*
  "RGB -> Okhsv, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Okhsv srgb)]
    (Vec4. (m/mnorm (.x c) 0.0 0.9999999496045209 0.0 255.0)
           (m/mnorm (.y c) 0.0 1.0119788696532530 0.0 255.0)
           (m/mnorm (.z c) 0.0 1.0000000319591997 0.0 255.0)
           (.w c))))

(defn from-Okhsv
  "Okhsv -> sRGB"
  ^Vec4 [okhsv]
  (let [^Vec4 hsv (pr/to-color okhsv)]
    (if (zero? (.z hsv))
      (Vec4. 0.0 0.0 0.0 (.w hsv))
      (let [h (* m/TWO_PI (.x hsv))
            a- (m/cos h)
            b- (m/sin h)
            ^Vec2 ST-max (->ST (find-cusp a- b-))
            S-max (.x ST-max)
            T-max (.y ST-max)
            k (- 1.0 (/ 0.5 S-max))
            r (/ (+ 0.5 (- T-max (* T-max k (.y hsv)))))
            Lv (- 1.0 (* 0.5 (.y hsv) r))
            Cv (* 0.5 (.y hsv) T-max r)
            L (* (.z hsv) Lv)
            C (* (.z hsv) Cv)
            Lvt (inv-toe Lv)
            Cvt (/ (* Cv Lvt) Lv)
            Lnew (inv-toe L)
            C (/ (* C Lnew) L)
            L Lnew
            ^Vec4 rgb-scale (Oklab->linear (Vec4. Lvt (* a- Cvt) (* b- Cvt) 1.0))
            scaleL (m/cbrt (/ (max (.x rgb-scale) (.y rgb-scale) (.z rgb-scale))))
            L (* L scaleL)
            C (* C scaleL)]
        (from-Oklab (Vec4. L (* C a-) (* C b-) (.w hsv)))))))

(defn from-Okhsv*
  "Okhsv -> sRGB, normalized"
  ^Vec4 [okhsv*]
  (let [^Vec4 c (pr/to-color okhsv*)]
    (from-Okhsv (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.9999999496045209)
                       (m/mnorm (.y c) 0.0 255.0 0.0 1.0119788696532530)
                       (m/mnorm (.z c) 0.0 255.0 0.0 1.0000000319591997)
                       (.w c)))))

;; Okhwb

(defn to-Okhwb
  "sRGB -> Okhwb

  Ranges:

  * h: 0.0 - 1.0
  * w: 0.0 - 1.0
  * b: 0.0 - 1.0"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Okhsv srgb)]
    (Vec4. (.x c) (* (- 1.0 (.y c)) (.z c)) (- 1.0 (.z c)) (.w c))))

(defn to-Okhwb*
  "RGB -> Okhwb, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Okhwb srgb)]
    (Vec4. (m/mnorm (.x c) 0.0 0.9999999496045209 0.0 255.0)
           (m/mnorm (.y c) -0.011902363837373111 0.999999923528539 0.0 255.0)
           (m/mnorm (.z c) -3.195919973109085E-8 1.0 0.0 255.0)
           (.w c))))

(defn from-Okhwb
  "Okhwb -> sRGB"
  ^Vec4 [okhwb]
  (let [^Vec4 c (pr/to-color okhwb)
        v (- 1.0 (.z c))]
    (if (zero? v)
      (Vec4. 0.0 0.0 0.0 (.w c))
      (from-Okhsv (Vec4. (.x c) (- 1.0 (/ (.y c) v)) v (.w c))))))

(defn from-Okhwb*
  "Okhwb -> sRGB, normalized"
  ^Vec4 [okhwb*]
  (let [^Vec4 c (pr/to-color okhwb*)]
    (from-Okhwb (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.9999999496045209)
                       (m/mnorm (.y c) 0.0 255.0 -0.011902363837373111 0.999999923528539)
                       (m/mnorm (.z c) 0.0 255.0 -3.195919973109085E-8 1.0                                )
                       (.w c)))))

;; Okhsl

(defn- get-ST-mid
  ^Vec2 [^double a ^double b]
  (Vec2. (+ 0.11516993 (/ (+ 7.44778970 (* 4.1590124 b)
                             (* a (+ -2.19557347 (* 1.7519840 b)
                                     (* a (+ -2.1370494 (* -10.0230104 b)
                                             (* a (+ -4.24894561 (* 5.38770819 b)
                                                     (* 4.69891013 a))))))))))
         (+ 0.11239642 (/ (+ 1.61320320 (* -0.68124379 b)
                             (* a (+ 0.40370612 (* 0.90148123 b)
                                     (* a (+ -0.27087943 (* 0.61223990 b)
                                             (* a (+ 0.00299215 (* -0.45399568 b)
                                                     (* -0.14661872 a))))))))))))

(defn- find-gamut-intersection
  ^double [^double a ^double b ^double L ^Vec2 cusp]
  ;; L0 = L1 = L, C1 = 1
  (if (<= L (.x cusp))
    (/ (* (.y cusp) L) (.x cusp))
    (let [t (/ (* (.y cusp) (dec L)) (dec (.x cusp)))
          #_#_ dL 0.0
          #_#_ dC 1.0
          kl (+ (* 0.3963377774 a) (* 0.2158037573 b))
          km (+ (* -0.1055613458 a) (* -0.0638541728 b))
          ks (+ (* -0.0894841775 a) (* -1.2914855480 b))
          L (+ (* L (- 1.0 t)) (* L t))
          C t
          l- (+ L (* C kl))
          m- (+ L (* C km))
          s- (+ L (* C ks))
          l (* l- l- l-)
          m (* m- m- m-)
          s (* s- s- s-)
          ldt (* 3.0 kl l- l-)
          mdt (* 3.0 km m- m-)
          sdt (* 3.0 ks s- s-)
          ldt2 (* 6.0 kl kl l-)
          mdt2 (* 6.0 km km m-)
          sdt2 (* 6.0 ks ks s-)
          r (dec (+ (* 4.0767416621 l) (* -3.3077115913 m) (* 0.2309699292 s)))
          r1 (+ (* 4.0767416621 ldt) (* -3.3077115913 mdt) (* 0.2309699292 sdt))
          r2 (+ (* 4.0767416621 ldt2) (* -3.3077115913 mdt2) (* 0.2309699292 sdt2))
          ur (/ r1 (- (* r1 r1) (* 0.5 r r2)))
          tr (* -1.0 r ur)
          g (dec (+ (* -1.2684380046 l) (* 2.6097574011 m) (* -0.3413193965 s)))
          g1 (+ (* -1.2684380046 ldt) (* 2.6097574011 mdt) (* -0.3413193965 sdt))
          g2 (+ (* -1.2684380046 ldt2) (* 2.6097574011 mdt2) (* -0.3413193965 sdt2))
          ug (/ g1 (- (* g1 g1) (* 0.5 g g2)))
          tg (* -1.0 g ug)
          b(dec (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s)))
          b1 (+ (* -0.0041960863 ldt) (* -0.7034186147 mdt) (* 1.7076147010 sdt))
          b2 (+ (* -0.0041960863 ldt2) (* -0.7034186147 mdt2) (* 1.7076147010 sdt2))
          ub (/ b1 (- (* b1 b1) (* 0.5 b b2)))
          tb (* -1.0 b ub)
          tr (if (>= ur 0.0) tr Double/MAX_VALUE)
          tg (if (>= ug 0.0) tg Double/MAX_VALUE)
          tb (if (>= ub 0.0) tb Double/MAX_VALUE)]
      (+ t (min tr tg tb)))))

(defn- get-Cs
  ^Vec3 [^double L ^double a ^double b]
  (let [^Vec2 cusp (find-cusp a b)
        C-max (find-gamut-intersection a b L cusp)
        ^Vec2 ST-max (->ST cusp)
        L- (- 1.0 L)
        k (/ C-max (min (* L (.x ST-max)) (* L- (.y ST-max))))
        ^Vec2 ST-mid (get-ST-mid a b)
        Ca (* L (.x ST-mid))
        Cb (* L- (.y ST-mid))
        C-mid (* 0.9 k (m/sqrt (m/sqrt (/ (+ (/ (m/sq (* Ca Ca)))
                                             (/ (m/sq (* Cb Cb))))))))
        Ca (* L 0.4)
        Cb (* L- 0.8)
        C-0 (m/sqrt (/ (+ (/ (* Ca Ca))
                          (/ (* Cb Cb)))))]
    (Vec3. C-0 C-mid C-max)))

(defn to-Okhsl
  "sRGB -> Okhsl

  Ranges:

  * h: 0.0 - 1.0
  * s: 0.0 - 1.0
  * l: 0.0 - 1.0"
  ^Vec4 [srgb]
  (let [^Vec4 lab (to-Oklab srgb)]
    (if (zero? (.x lab))
      (Vec4. 0.0 0.0 0.0 (.w lab))
      (let [C (m/hypot-sqrt (.y lab) (.z lab))
            a- (/ (.y lab) C)
            b- (/ (.z lab) C)
            L (.x lab)
            h (+ 0.5 (* 0.5 (/ (m/atan2 (- (.z lab)) (- (.y lab))) m/PI)))
            ^Vec3 cs (get-Cs L a- b-)
            C-0 (.x cs)
            C-mid (.y cs)
            C-max (.z cs)
            s (if (< C C-mid)
                (let [k1 (* 0.8 C-0)
                      k2 (- 1.0 (/ k1 C-mid))]
                  (* 0.8 (/ C (+ k1 (* k2 C)))))
                (let [k1 (/ (* 0.3125 C-mid C-mid) C-0)
                      k2 (- 1.0 (/ k1 (- C-max C-mid)))]
                  (+ 0.8 (* 0.2 (/ (- C C-mid)
                                   (+ k1 (* k2 (- C C-mid))))))))]
        (Vec4. h s (toe L) (.w lab))))))

(defn to-Okhsl*
  "sRGB -> Okhsl, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-Okhsl srgb)]
    (Vec4. (m/mnorm (.x c) 0.0 0.999999949604520 0.0 255.0)
           (m/mnorm (.y c) 0.0 1.012343260867314 0.0 255.0)
           (m/mnorm (.z c) 0.0 0.999999992396189 0.0 255.0)
           (.w c))))

(defn from-Okhsl
  "Okhsl -> sRGB"
  ^Vec4 [okhsl]
  (let [^Vec4 hsl (pr/to-color okhsl)]
    (if (zero? (.z hsl))
      (Vec4. 0.0 0.0 0.0 (.w hsl))
      (let [h (* m/TWO_PI (.x hsl))
            a- (m/cos h)
            b- (m/sin h)
            L (inv-toe (.z hsl))
            ^Vec3 cs (get-Cs L a- b-)
            C-0 (.x cs)
            C-mid (.y cs)
            C-max (.z cs)
            C (if (< (.y hsl) 0.8)
                (let [t (* 1.25 (.y hsl))
                      k1 (* 0.8 C-0)
                      k2 (- 1.0 (/ k1 C-mid))]
                  (/ (* t k1) (- 1.0 (* t k2))))
                (let [t (/ (- (.y hsl) 0.8) 0.2)
                      k1 (/ (* 0.3125 C-mid C-mid) C-0)
                      k2 (- 1.0 (/ k1 (- C-max C-mid)))]
                  (+ C-mid (/ (* t k1) (- 1.0 (* t k2))))))]
        (from-Oklab (Vec4. L (* C a-) (* C b-) (.w hsl)))))))

(defn from-Okhsl*
  "Okhsl -> sRGB, normalized"
  ^Vec4 [okhsl*]
  (let [^Vec4 c (pr/to-color okhsl*)]
    (from-Okhsl (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.999999949604520)
                       (m/mnorm (.y c) 0.0 255.0 0.0 1.012343260867314)
                       (m/mnorm (.z c) 0.0 255.0 0.0 0.999999992396189)
                       (.w c)))))

;; ### XYZ

(defn ->XYZ-to-XYZ
  "Create XYZ converter between different white points and given adaptation method.

  Adaptation method can be one of: `:xyz-scaling`, `:bradford` (default), `:von-kries`, `:sharp`, `:fairchild`, `:cat97`, `:cat2000`, `:cat02`, `:cat02brill2008`, `:cat16`, `bianco2010`, `:bianco2010-pc`."
  ([source-wp destination-wp] (->XYZ-to-XYZ :bradford source-wp destination-wp))
  ([adaptation-method source-wp destination-wp]
   (let [M (wp/chromatic-adaptation-matrix adaptation-method source-wp destination-wp)]
     (fn ^Vec4 [xyz]
       (let [^Vec4 c (pr/to-color xyz)
             ^Vec3 c3 (mat/mulv M (Vec3. (.x c) (.y c) (.z c)))]
         (Vec4. (.x c3) (.y c3) (.z c3) (.w c)))))))

(defn XYZ-to-XYZ1
  ^Vec4 [xyz]
  (let [^Vec4 c (pr/to-color xyz)]
    (Vec4. (* 0.01 (.x c))
           (* 0.01 (.y c))
           (* 0.01 (.z c))
           (.w c))))

(defn XYZ1-to-XYZ
  ^Vec4 [xyz1]
  (let [^Vec4 c (pr/to-color xyz1)]
    (Vec4. (* 100.0 (.x c))
           (* 100.0 (.y c))
           (* 100.0 (.z c))
           (.w c))))

(defn- to-XYZ-
  "Pure RGB->XYZ conversion without corrections."
  ^Vec3 [^Vec3 c]
  (Vec3. (+ (* (.x c) 0.4124564390896921) (* (.y c) 0.357576077643909) (* (.z c) 0.18043748326639894))
         (+ (* (.x c) 0.21267285140562248) (* (.y c) 0.715152155287818) (* (.z c) 0.07217499330655958))
         (+ (* (.x c) 0.019333895582329317) (* (.y c) 0.119192025881303) (* (.z c) 0.9503040785363677))))

(defn to-XYZ1
  "sRGB -> XYZ (CIE2 D65), scaled to range 0-1"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [^Vec4 c (pr/to-color srgb)
        xyz-raw (to-XYZ- (-> (Vec3. (.x c) (.y c) (.z c))
                             (v/div 255.0)
                             (v/fmap rgb/srgb-to-linear)))]
    (v/vec4 xyz-raw (.w c))))

(defn to-XYZ
  "sRGB -> XYZ (CIE2 D65)

  Returned ranges (D65):

  * X: 0.0 - 95.047
  * Y: 0.0 - 100.0
  * Z: 0.0 - 108.883"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (XYZ1-to-XYZ (to-XYZ1 srgb)))

(defn to-XYZ*
  "sRGB -> XYZ (CIE2 D65), normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-XYZ srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 95.04715475817799 0.0 255.0)
           (m/mnorm (.y cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.z cc) 0.0 108.8829656285427 0.0 255.0)
           (.w cc))))

(def ^{:doc "sRGB -> XYZ (CIE2 D65), normalized" :metadoc/categories meta-conv} to-XYZ1* to-XYZ*)

(defn- from-XYZ-
  "Pure XYZ->RGB conversion."
  ^Vec3 [^Vec3 v]
  (Vec3. (+ (* (.x v) 3.2404541621141054) (* (.y v) -1.5371385127977166) (* (.z v) -0.4985314095560162))
         (+ (* (.x v) -0.9692660305051868) (* (.y v)  1.8760108454466942) (* (.z v)  0.04155601753034984))
         (+ (* (.x v)  0.05564343095911469) (* (.y v) -0.20402591351675387) (* (.z v) 1.0572251882231791))))

(defn from-XYZ1
  "XYZ (CIE2 D65) -> sRGB, from range 0-1"
  ^Vec4 [xyz1]
  (let [^Vec4 c (pr/to-color xyz1)
        rgb-raw (v/mult (v/fmap (from-XYZ- (Vec3. (.x c) (.y c) (.z c))) rgb/linear-to-srgb) 255.0)]
    (v/vec4 rgb-raw (.w c))))

(defn from-XYZ
  "XYZ (CIE2 D65) -> sRGB

  For ranges, see [[to-XYZ]]"
  {:metadoc/categories meta-conv}
  ^Vec4 [xyz1] 
  (from-XYZ1 (XYZ-to-XYZ1 xyz1)))

(defn from-XYZ*
  "XYZ (CIE2 D65) -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [xyz*]
  (let [^Vec4 c (pr/to-color xyz*)
        x (m/mnorm (.x c) 0.0 255.0 0.0 95.04715475817799)
        y (m/mnorm (.y c) 0.0 255.0 0.0 100.0)
        z (m/mnorm (.z c) 0.0 255.0 0.0 108.8829656285427)]
    (from-XYZ (Vec4. x y z (.w c)))))

(def ^{:doc "XYZ (CIE2 D65) -> sRGB, normalized" :metadoc/categories meta-conv} from-XYZ1* from-XYZ*)

;; https://en.wikipedia.org/wiki/CIE_1960_color_space#Relation_to_CIE_XYZ

(defn XYZ-to-UCS
  "XYZ -> UCS"
  ^Vec4 [xyz]
  (let [^Vec4 c (XYZ-to-XYZ1 xyz)]
    (Vec4. (* m/TWO_THIRD (.x c))
           (.y c)
           (* -0.5 (- (.x c) (* 3.0 (.y c)) (.z c)))
           (.w c))))

(defn to-UCS
  "sRGB -> UCS

  * U: 0.0 - 0.63
  * V: 0.0 - 1.0
  * W: 0.0 - 1.57"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (-> srgb to-XYZ XYZ-to-UCS))

(defn to-UCS*
  "sRGB -> UCS, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [^Vec4 cc (to-UCS srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 0.633646666666666 0.0 255.0)
           (* (.y cc) 255.0)
           (m/mnorm (.z cc) 0.0 1.569179999999999 0.0 255.0)
           (.w cc))))

(defn UCS-to-XYZ
  ^Vec4 [ucs]
  (let [^Vec4 c (pr/to-color ucs)]
    (XYZ1-to-XYZ (Vec4. (* 1.5 (.x c))
                        (.y c)
                        (+ (* 1.5 (.x c))
                           (* -3.0 (.y c))
                           (* 2.0 (.z c)))
                        (.w c)))))

(defn from-UCS
  "UCS -> sRGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [ucs]
  (-> ucs UCS-to-XYZ from-XYZ))

(defn from-UCS*
  "UCS -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [ucs*]
  (let [^Vec4 cc (pr/to-color ucs*)]
    (from-UCS (Vec4. (m/mnorm (.x cc) 0.0 255.0 0.0 0.633646666666666)
                     (/ (.y cc) 255.0)
                     (m/mnorm (.z cc) 0.0 255.0 0.0 1.569179999999999)
                     (.w cc)))))

;; ### XYB, https://observablehq.com/@mattdesl/perceptually-smooth-multi-color-linear-gradients

(defn- convert-mix
  ^double [^double m]
  (-> m (max 0.0) m/cbrt (+ -0.15595420054924863)))

(defn to-XYB
  "sRGB -> XYB

  * X: -0.015386116472573375 - 0.02810008316127735
  * Y: 0.0 - 0.8453085619621623
  * B: 0.0 - 0.8453085619621623"
  ^Vec4 [srgb]
  (let [^Vec4 c (from-sRGB srgb)
        r (convert-mix (m/muladd 0.001176470588235294 (.x c)
                                 (m/muladd 0.00243921568627451 (.y c)
                                           (m/muladd 3.0588235294117644E-4 (.z c)
                                                     0.0037930732552754493))))
        g (convert-mix (m/muladd 9.019607843137256E-4 (.x c)
                                 (m/muladd 0.0027137254901960788 (.y c)
                                           (m/muladd 3.0588235294117644E-4 (.z c)
                                                     0.0037930732552754493))))
        b (convert-mix (m/muladd 9.545987813548164E-4 (.x c)
                                 (m/muladd 8.030095852743851E-4 (.y c)
                                           (m/muladd 0.002163960260821779 (.z c)
                                                     0.0037930732552754493))))]
    (Vec4. (* 0.5 (- r g)) (* 0.5 (+ r g)) b (.w c))))

(defn to-XYB*
  "sRGB -> XYB, normalized"
  ^Vec4 [srgb]
  (let [^Vec4 c (to-XYB srgb)]
    (Vec4. (m/mnorm (.x c) -0.015386116472573375 0.02810008316127735 0.0 255.0)
           (m/mnorm (.y c) 0.0 0.8453085619621623 0.0 255.0)
           (m/mnorm (.z c) 0.0 0.8453085619621623 0.0 255.0)
           (.w c))))

(defn from-XYB
  "XYB -> sRGB"
  ^Vec4 [xyb]
  (let [^Vec4 c (pr/to-color xyb)
        gamma-r (- (+ (.y c) (.x c)) -0.15595420054924863)
        gamma-g (- (.y c) (.x c) -0.15595420054924863)
        gamma-b (- (.z c) -0.15595420054924863)
        mixed-r (m/muladd (* gamma-r gamma-r) gamma-r -0.0037930732552754493)
        mixed-g (m/muladd (* gamma-g gamma-g) gamma-g -0.0037930732552754493)
        mixed-b (m/muladd (* gamma-b gamma-b) gamma-b -0.0037930732552754493)
        r (m/muladd -41.9788641 mixed-b (m/muladd -2516.0707 mixed-g (* 2813.04956 mixed-r)))
        g (m/muladd -41.9788641 mixed-b (m/muladd 1126.78645 mixed-g (* -829.807582 mixed-r)))
        b (m/muladd 496.211701 mixed-b (m/muladd 691.795377 mixed-g (* -933.007078 mixed-r)))]
    (to-sRGB (Vec4. r g b (.w c)))))

(defn from-XYB*
  "XYB -> sRGB, normalized"
  ^Vec4 [xyb*]
  (let [^Vec4 c (pr/to-color xyb*)]
    (from-XYB (Vec4. (m/mnorm (.x c) 0.0 255.0 -0.015386116472573375 0.02810008316127735)
                     (m/mnorm (.y c) 0.0 255.0 0.0 0.8453085619621623)
                     (m/mnorm (.z c) 0.0 255.0 0.0 0.8453085619621623)
                     (.w c)))))

;; ### RYB
;; https://web.archive.org/web/20120302090118/http://www.insanit.net/tag/rgb-to-ryb/

(defn to-RYB
  "sRGB -> RYB"
  ^Vec4 [srgb]
  (let [^Vec4 c (pr/to-color srgb)
        w (min (.x c) (.y c) (.z c))
        r (- (.x c) w)
        g (- (.y c) w)
        b (- (.z c) w)
        mg (max r g b)
        y (min r g)
        r (- r y)
        g (- g y)
        nz? (and (not (zero? b))
                 (not (zero? g)))
        g (if nz? (/ g 2.0) g)
        b (if nz? (/ b 2.0) b)
        y (+ y g)
        b (+ b g)
        my (max r y b)
        n (if-not (zero? my) (/ mg my) 1.0)]
    (Vec4. (m/muladd r n w)
           (m/muladd y n w)
           (m/muladd b n w)
           (.w c))))

(def ^{:doc "sRGB -> RYB, normalized" :metadoc/categories meta-conv} to-RYB* to-RYB)

(defn from-RYB
  "RYB -> sRGB"
  ^Vec4 [ryb]
  (let [^Vec4 c (pr/to-color ryb)
        w (min (.x c) (.y c) (.z c))
        r (- (.x c) w)
        y (- (.y c) w)
        b (- (.z c) w)
        my (max r y b)
        g (min y b)
        y (- y g)
        b (- b g)
        nz? (and (not (zero? b))
                 (not (zero? g)))
        b (if nz? (* 2.0 b) b)
        g (if nz? (* 2.0 g) g)
        r (+ r y)
        g (+ g y)
        mg (max r g b)
        n (if-not (zero? mg) (/ my mg) 1.0)]
    (Vec4. (m/muladd r n w)
           (m/muladd g n w)
           (m/muladd b n w)
           (.w c))))

(def ^{:doc "RYB -> sRGB, normalized" :metadoc/categories meta-conv} from-RYB* from-RYB)

;; ### LAB

(def ^{:private true :const true :tag 'double} CIEEpsilon (/ 216.0 24389.0))
(def ^{:private true :const true :tag 'double} CIEK (/ 24389.0 27.0))

(defn- to-lab-correct
  "LAB correction"
  ^double [^double v]
  (if (> v CIEEpsilon)
    (m/cbrt v)
    (/ (+ 16.0 (* v CIEK)) 116.0)))

(defn XYZ-to-LAB
  "XYZ to LAB conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [xyz] (XYZ-to-LAB xyz wp/CIE-2-D65))
  (^Vec4 [xyz ^Vec4 whitepoint]
   (let [xyz (XYZ-to-XYZ1 xyz)
         x (to-lab-correct (/ (.x xyz) (.x whitepoint)))
         y (to-lab-correct (.y xyz))
         z (to-lab-correct (/ (.z xyz) (.y whitepoint)))
         L (- (* y 116.0) 16.0)
         a (* 500.0 (- x y))
         b (* 200.0 (- y z))]
     (Vec4. L a b (.w xyz)))))

(defn to-LAB
  "sRGB -> LAB

  Returned ranges:

  * L: 0.0 - 100.0
  * a: -86.18 - 98.25
  * b: -107.86 - 94.48"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (-> srgb to-XYZ XYZ-to-LAB))

(defn to-LAB*
  "sRGB -> LAB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-LAB srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) -86.18463649762525 98.25421868616108 0.0 255.0)
           (m/mnorm (.z cc) -107.86368104495168 94.48248544644461 0.0 255.0)
           (.w cc))))

(defn- from-lab-correct
  "LAB correction"
  ^double [^double v]
  (let [v3 (* v v v)]
    (if (> v3 CIEEpsilon)
      v3
      (/ (- (* 116.0 v) 16.0) CIEK))))

(defn LAB-to-XYZ
  "LAB to XYZ conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [lab] (LAB-to-XYZ lab wp/CIE-2-D65))
  (^Vec4 [lab ^Vec4 whitepoint]
   (let [^Vec4 c (pr/to-color lab)
         y (/ (+ (.x c) 16.0) 116.0)
         x (* (.x whitepoint) (from-lab-correct (+ y (/ (.y c) 500.0))))
         z (* (.y whitepoint) (from-lab-correct (- y (/ (.z c) 200.0))))]
     (XYZ1-to-XYZ (Vec4. x (from-lab-correct y) z (.w c))))))

(defn from-LAB
  "LAB -> sRGB,

  For ranges, see [[to-LAB]]"
  {:metadoc/categories meta-conv}
  ^Vec4 [lab]
  (-> lab LAB-to-XYZ from-XYZ))

(defn from-LAB*
  "LAB -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [lab*]
  (let [^Vec4 c (pr/to-color lab*)]
    (from-LAB (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                     (m/mnorm (.y c) 0.0 255.0 -86.18463649762525 98.25421868616108)
                     (m/mnorm (.z c) 0.0 255.0 -107.86368104495168 94.48248544644461)
                     (.w c)))))

;;

(defn XYZ-to-LUV
  "XYZ to LUV conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [xyz] (XYZ-to-LUV xyz wp/CIE-2-D65))
  (^Vec4 [xyz ^Vec4 whitepoint]
   (let [^Vec4 cc (pr/to-color xyz)
         uv-factor (+ (.x cc) (* 15.0 (.y cc)) (* 3.0 (.z cc)))]
     (if (zero? uv-factor)
       (Vec4. 0.0 0.0 0.0 (.w cc))
       (let [uv-factor* (/ uv-factor)
             var-u (* 4.0 (.x cc) uv-factor*)
             var-v (* 9.0 (.y cc) uv-factor*)
             var-y (to-lab-correct (/ (.y cc) 100.0))
             ref-uv-factor (/ (+ (.x whitepoint) 15.0 (* 3.0 (.y whitepoint))))
             ref-u (* 4.0 (.x whitepoint) ref-uv-factor)
             ref-v (* 9.0 ref-uv-factor)
             L (- (* 116.0 var-y) 16.0)] 
         (Vec4. L
                (* 13.0 L (- var-u ref-u))
                (* 13.0 L (- var-v ref-v))
                (.w cc)))))))

(defn to-LUV
  "sRGB -> LUV

  Returned ranges:

  * L: 0.0 - 100.0
  * u: -83.08 - 175.05
  * v: -134.12 - 107.40"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (-> srgb to-XYZ XYZ-to-LUV))

(defn to-LUV*
  "sRGB -> LUV, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-LUV srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) -83.07975193131836 175.05303573649485 0.0 255.0)
           (m/mnorm (.z cc) -134.1160763907768 107.40136474095397 0.0 255.0)
           (.w cc))))

(defn LUV-to-XYZ
  "LUV to XYZ conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [luv] (LUV-to-XYZ luv wp/CIE-2-D65))
  (^Vec4 [luv ^Vec4 whitepoint]
   (let [^Vec4 c (pr/to-color luv)]
     (if (zero? (.x c))
       (Vec4. 0.0 0.0 0.0 (.w c))
       (let [ref-uv-factor (/ (+ (.x whitepoint) 15.0 (* 3.0 (.y whitepoint))))
             ref-u (* 4.0 (.x whitepoint) ref-uv-factor)
             ref-v (* 9.0 ref-uv-factor)
             var-y (from-lab-correct (/ (+ (.x c) 16.0) 116.0))
             var-u (+ ref-u (/ (.y c) (* 13.0 (.x c))))
             var-v (+ ref-v (/ (.z c) (* 13.0 (.x c))))
             Y (* 100.0 var-y)
             X (/ (* -9.0 Y var-u) (- (* (- var-u 4.0) var-v) (* var-u var-v)))]
         (Vec4. X
                Y
                (/ (- (* 9.0 Y) (* 15.0 var-v Y) (* var-v X)) (* 3.0 var-v))
                (.w c)))))))

(defn from-LUV
  "LUV -> RGB

  For ranges, see [[to-LUV]]"
  {:metadoc/categories meta-conv}
  ^Vec4 [luv]
  (-> luv LUV-to-XYZ from-XYZ))

(defn from-LUV*
  "LUV -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [luv*]
  (let [^Vec4 c (pr/to-color luv*)]
    (from-LUV (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                     (m/mnorm (.y c) 0.0 255.0 -83.07975193131836 175.05303573649485)
                     (m/mnorm (.z c) 0.0 255.0 -134.1160763907768 107.40136474095397)
                     (.w c)))))

;; HunterLab

(def ^{:private true :const true :tag 'double} Ka (* (/ 17500.0 198.04)))
(def ^{:private true :const true :tag 'double} Kb (* (/ 7000.0 218.11)))

(defn XYZ-to-HunterLAB
  "XYZ to HunterLAB conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [xyz] (XYZ-to-HunterLAB xyz wp/CIE-2-D65))
  (^Vec4 [xyz ^Vec4 whitepoint]
   (let [cc (XYZ-to-XYZ1 xyz)]
     (if (zero? (.y cc))
       (Vec4. 0.0 0.0 0.0 (.w cc))
       (let [X (/ (.x cc) (.x whitepoint))
             sqrtY (m/sqrt (.y cc))
             Z (/ (.z cc) (.y whitepoint))
             ka (* Ka (m/inc (.x whitepoint)))
             kb (* Kb (m/inc (.y whitepoint)))]
         (Vec4. (* 100.0 sqrtY)
                (* ka (/ (- X (.y cc)) sqrtY))
                (* kb (/ (- (.y cc) Z) sqrtY))
                (.w cc)))))))

(defn to-HunterLAB
  "sRGB -> HunterLAB

  Returned ranges:

  * L: 0.0 - 100.0
  * a: -69.08 - 109.48
  * b: -199.78 - 55.72"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (-> srgb to-XYZ XYZ-to-HunterLAB))

(defn to-HunterLAB*
  "sRGB -> HunterLAB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-HunterLAB srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) -69.08211393661531 109.48378856734126 0.0 255.0)
           (m/mnorm (.z cc) -199.78221402287008  55.7203132978682 0.0 255.0)
           (.w cc))))

(defn HunterLAB-to-XYZ
  "HunterLAB to XYZ conversion for given white point (default: CIE-2-D65)"
  (^Vec4 [hunterlab] (HunterLAB-to-XYZ hunterlab wp/CIE-2-D65))
  (^Vec4 [hunterlab ^Vec4 whitepoint]
   (let [^Vec4 c (pr/to-color hunterlab)]
     (if (zero? (.x c))
       (Vec4. 0.0 0.0 0.0 (.w c))
       (let [Y (m/sq (/ (.x c) 100.0))
             sqrtY (m/sqrt Y)
             ka (* Ka (m/inc (.x whitepoint)))
             kb (* Kb (m/inc (.y whitepoint)))
             X (* (.x whitepoint) (+ (* (/ (.y c) ka) sqrtY) Y))
             Z (- (* (.y whitepoint) (- (* (/ (.z c) kb) sqrtY) Y)))]
         (XYZ1-to-XYZ (Vec4. X Y Z (.w c))))))))

(defn from-HunterLAB
  "HunterLAB -> RGB

  For ranges, see [[to-HunterLAB]]"
  {:metadoc/categories meta-conv}
  ^Vec4 [hunterlab]
  (-> hunterlab HunterLAB-to-XYZ from-XYZ))

(defn from-HunterLAB*
  "HunterLAB -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [hunterlab*]
  (let [^Vec4 c (pr/to-color hunterlab*)]
    (from-HunterLAB (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                           (m/mnorm (.y c) 0.0 255.0 -69.08211393661531 109.48378856734126)
                           (m/mnorm (.z c) 0.0 255.0 -199.78221402287008  55.7203132978682)
                           (.w c)))))

;;

(defn XYZ-to-LCH
  (^Vec4 [xyz] (XYZ-to-LCH xyz wp/CIE-2-D65))
  (^Vec4 [xyz whitepoint]
   (to-luma-color-hue (XYZ-to-LAB xyz whitepoint))))

(defn to-LCH
  "sRGB -> LCH

  Returned ranges:

  * L: 0.0 - 100.0
  * C: 0.0 - 133.82
  * H: 0.0 - 360.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (to-luma-color-hue to-LAB srgb))

(defn to-LCH*
  "sRGB -> LCH, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-LCH srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) 0.0 133.81586201619493 0.0 255.0)
           (m/mnorm (.z cc) 0.0 359.99994682530985 0.0 255.0)
           (.w cc))))

(defn LCH-to-XYZ
  (^Vec4 [lch] (LCH-to-XYZ lch wp/CIE-2-D65))
  (^Vec4 [lch whitepoint]
   (LAB-to-XYZ (from-luma-color-hue lch) whitepoint)))

(defn from-LCH
  "LCH -> sRGB

  For ranges, see [[to-LCH]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [lch]
  (from-luma-color-hue from-LAB lch))

(defn from-LCH*
  "LCH -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [lch*]
  (let [^Vec4 c (pr/to-color lch*)]
    (from-LCH (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                     (m/mnorm (.y c) 0.0 255.0 0.0 133.81586201619493)
                     (m/mnorm (.z c) 0.0 255.0 0.0 359.99994682530985)
                     (.w c)))))

;; LCHuv

(defn XYZ-to-LCHuv
  (^Vec4 [xyz] (XYZ-to-LCHuv xyz wp/CIE-2-D65))
  (^Vec4 [xyz whitepoint]
   (to-luma-color-hue (XYZ-to-LUV xyz whitepoint))))

(defn to-LCHuv
  "sRGB -> LCHuv

  Returned ranges:

  * L: 0.0 - 100.0
  * C: 0.0 - 180.0
  * H: 0.0 - 360.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (to-luma-color-hue to-LUV srgb))

(defn to-LCHuv*
  "sRGB -> LCHuv, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-LCHuv srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) 0.0 179.04142708939605 0.0 255.0)
           (m/mnorm (.z cc) 0.0 359.99994137350546 0.0 255.0)
           (.w cc))))

(defn LCHuv-to-XYZ
  (^Vec4 [lchuv] (LCHuv-to-XYZ lchuv wp/CIE-2-D65))
  (^Vec4 [lchuv whitepoint]
   (LUV-to-XYZ (from-luma-color-hue lchuv) whitepoint)))

(defn from-LCHuv
  "LCHuv -> sRGB

  For ranges, see [[to-LCH]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [lchuv]
  (from-luma-color-hue from-LUV lchuv))

(defn from-LCHuv*
  "LCHuv -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [lchuv*]
  (let [^Vec4 c (pr/to-color lchuv*)]
    (from-LCHuv (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                       (m/mnorm (.y c) 0.0 255.0 0.0 179.04142708939605)
                       (m/mnorm (.z c) 0.0 255.0 0.0 359.99994137350546)
                       (.w c)))))

;; ### Yxy (xyY)

(defn XYZ-to-Yxy
  (^Vec4 [xyz] (XYZ-to-Yxy xyz wp/CIE-2-D65))
  (^Vec4 [xyz ^Vec4 whitepoint]
   (let [^Vec4 xyz (pr/to-color xyz)
         d (+ (.x xyz) (.y xyz) (.z xyz))]
     (if (zero? d)
       (Vec4. 0.0 (.z whitepoint) (.w whitepoint) (.w xyz))
       (Vec4. (.y xyz)
              (/ (.x xyz) d)
              (/ (.y xyz) d)
              (.w xyz))))))

(defn to-Yxy
  "sRGB -> Yxy

  Returned ranges:

  * Y: 0.0 - 100.0
  * x: 0.15 - 0.64
  * y: 0.06 - 0.60"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (XYZ-to-Yxy (to-XYZ srgb)))

(defn to-Yxy*
  "sRGB -> Yxy, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-Yxy srgb)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.0 0.0 255.0)
           (m/mnorm (.y cc) 0.0 0.640074499456775 0.0 255.0)
           (m/mnorm (.z cc) 0.0 0.6000000000000001 0.0 255.0)
           (.w cc))))

(defn Yxy-to-XYZ
  ^Vec4 [yxy]
  (let [^Vec4 c (pr/to-color yxy)]
    (if (zero? (.x c))
      (Vec4. 0.0 0.0 0.0 (.w c))
      (let [Yy (/ (.x c) (.z c))
            X (* (.y c) Yy) 
            Z (* (- 1.0 (.y c) (.z c)) Yy)]
        (Vec4. X (.x c) Z (.w c))))))

(defn from-Yxy
  "Yxy -> sRGB

  For ranges, see [[to-Yxy]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [yxy]
  (from-XYZ (Yxy-to-XYZ yxy)))

(defn from-Yxy*
  "Yxy -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [yxy*]
  (let [^Vec4 c (pr/to-color yxy*)]
    (from-Yxy (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.0)
                     (m/mnorm (.y c) 0.0 255.0 0.0 0.640074499456775)
                     (m/mnorm (.z c) 0.0 255.0 0.0 0.6000000000000001)
                     (.w c)))))

;; UVW, https://en.wikipedia.org/wiki/CIE_1964_color_space

(defn- xy->uv
  (^Vec2 [^Vec2 xy] (xy->uv (.x xy) (.y xy)))
  (^Vec2 [^double x ^double y]
   (let [d (/ (+ (* 12.0 y)
                 (* -2.0 x)
                 3.0))]
     (Vec2. (* 4.0 x d)
            (* 6.0 y d)))))

(defn- uv->xy
  (^Vec2 [^Vec2 uv] (uv->xy (.x uv) (.y uv)))
  (^Vec2 [^double u ^double v]
   (let [d (/ (+ (* 2.0 u)
                 (* -8.0 v)
                 4.0))]
     (Vec2. (* 3.0 u d)
            (* 2.0 v d)))))

(defn XYZ-to-UVW
  (^Vec4 [xyz] (XYZ-to-UVW xyz wp/CIE-2-D65))
  (^Vec4 [xyz ^Vec4 whitepoint]
   (let [^Vec4 c (XYZ-to-Yxy xyz whitepoint)
         ^Vec2 uv0 (xy->uv (Vec2. (.z whitepoint) (.w whitepoint)))
         ^Vec2 uv (v/sub (xy->uv (Vec2. (.y c) (.z c))) uv0)
         W (- (* 25.0 (m/cbrt (.x c))) 17.0)
         W13 (* 13.0 W)]
     (Vec4. (* W13 (.x uv)) (* W13 (.y uv)) W (.w c)))))

(defn to-UVW
  "sRGB -> UVW

  * U: -82.15 171.81
  * V: -87.16 70.82
  * W: -17.0 99.0"
  ^Vec4 [srgb]
  (XYZ-to-UVW (to-XYZ srgb)))

(defn to-UVW*
  "sRGB -> UVW, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [srgb]
  (let [cc (to-UVW srgb)]
    (Vec4. (m/mnorm (.x cc) -82.16463650546362 171.80558838900603 0.0 255.0)
           (m/mnorm (.y cc) -87.16901056149129 70.81193420964753 0.0 255.0)
           (m/mnorm (.z cc) -17.0 99.03972084031946 0.0 255.0)
           (.w cc))))

(defn UVW-to-XYZ
  (^Vec4 [uvw] (UVW-to-XYZ uvw wp/CIE-2-D65))
  (^Vec4 [uvw ^Vec4 whitepoint]
   (let [^Vec4 c (pr/to-color uvw)
         ^Vec2 uv0 (xy->uv (Vec2. (.z whitepoint) (.w whitepoint)))
         Y (m/cb (/ (+ 17.0 (.z c)) 25.0))
         W13 (/ (* 13.0 (.z c)))
         ^Vec2 xy (uv->xy (Vec2. (+ (* (.x c) W13) (.x uv0))
                                 (+ (* (.y c) W13) (.y uv0))))]
     (Yxy-to-XYZ (Vec4. Y (.x xy) (.y xy) (.w c))))))

(defn from-UVW
  "UVW -> sRGB"
  ^Vec4 [uvw]
  (from-XYZ (UVW-to-XYZ uvw)))

(defn from-UVW*
  "UVW -> sRGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [uvw*]
  (let [^Vec4 cc (pr/to-color uvw*)]
    (from-UVW (Vec4. (m/mnorm (.x cc) 0.0 255.0 -82.16463650546362 171.80558838900603)
                     (m/mnorm (.y cc) 0.0 255.0 -87.16901056149129 70.81193420964753)
                     (m/mnorm (.z cc) 0.0 255.0 -17.0 99.03972084031946)
                     (.w cc)))))

;; ### LMS - normalized D65

(defn ->XYZ-to-LMS
  ""
  [method]
  (let [m (first (wp/chromatic-adaptation-methods method))]
    (fn XYZ-to-LMS ^Vec4 [xyz]
      (let [^Vec4 xyz (pr/to-color xyz)]
        (v/vec4 (mat/mulv m (Vec3. (.x xyz) (.y xyz) (.z xyz))) (.w xyz))))))

(defn to-LMS
  "RGB -> LMS, D65

  Ranges: 0.0 - 100.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [c (to-XYZ c)]
    (Vec4. (+ (* 0.40024 (.x c)) (* 0.7076 (.y c)) (* -0.08081 (.z c)))
           (+ (* -0.2263 (.x c)) (* 1.16532 (.y c)) (* 0.0457 (.z c)))
           (* 0.91822 (.z c))
           (.w c))))

(defn to-LMS*
  "RGB -> LMS, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-LMS c)]
    (Vec4. (m/mnorm (.x cc) 0.0 100.00260300000001 0.0 255.0)
           (m/mnorm (.y cc) 0.0 99.998915 0.0 255.0)
           (m/mnorm (.z cc) 0.0 99.994158 0.0 255.0)
           (.w cc))))

(defn ->LMS-to-XYZ
  ""
  [method]
  (let [m (second (wp/chromatic-adaptation-methods method))]
    (fn LMS-to-XYZ ^Vec4 [lms]
      (let [^Vec4 lms (pr/to-color lms)]
        (v/vec4 (mat/mulv m (Vec3. (.x lms) (.y lms) (.z lms))) (.w lms))))))

(defn from-LMS
  "LMS -> RGB, D65

  Ranges: 0.0 - 100.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-XYZ (Vec4. (+ (* 1.8599363874558397 (.x c))
                        (* -1.1293816185800916 (.y c))
                        (* 0.2198974095961933 (.z c)))
                     (+ (* 0.3611914362417676 (.x c))
                        (* 0.6388124632850422 (.y c))
                        (* -0.0000063705968386499 (.z c)))
                     (* 1.0890636230968613 (.z c))
                     (.w c)))))

(defn from-LMS*
  "LMS -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-LMS (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 100.00260300000001)
                     (m/mnorm (.y c) 0.0 255.0 0.0 99.998915)
                     (m/mnorm (.z c) 0.0 255.0 0.0 99.994158)
                     (.w c)))))

;; IPT
;;
;; https://www.researchgate.net/publication/221677980_Development_and_Testing_of_a_Color_Space_IPT_with_Improved_Hue_Uniformity

(defmacro ^:private spow 
  "Symmetric pow"
  [v e]
  `(if (neg? ~v)
     (- (m/pow (- ~v) ~e))
     (m/pow ~v ~e)))

(defn- spow-043
  ^double [^double v]
  (spow v 0.43))

(defn- spow-r043
  ^double [^double v]
  (spow v 2.3255813953488373))


(defn to-IPT
  "RGB -> IPT

  Ranges:

  * I: 0.0 - 1.0
  * P: -0.45 - 0.66
  * T: -0.75 - 0.65"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [c (to-XYZ1 c)
        ^Vec3 LMS (-> (Vec3. (+ (* 0.4002 (.x c)) (* 0.7075 (.y c)) (* -0.0807 (.z c)))
                             (+ (* -0.228 (.x c)) (* 1.15 (.y c)) (* 0.0612 (.z c)))
                             (* 0.9184 (.z c)))
                      (v/fmap spow-043))]
    (Vec4. (+ (* 0.4 (.x LMS)) (* 0.4 (.y LMS)) (* 0.2 (.z LMS)))
           (+ (* 4.455 (.x LMS)) (* -4.851 (.y LMS)) (* 0.396 (.z LMS)))
           (+ (* 0.8056 (.x LMS)) (* 0.3572 (.y LMS)) (* -1.1628 (.z LMS)))
           (.w c))))

(defn to-IPT*
  "RGB -> IPT, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-IPT c)]
    (Vec4. (m/mnorm (.x cc) 0.0 0.99998787116167 0.0 255.0)
           (m/mnorm (.y cc) -0.45339260927924635 0.662432531162485 0.0 255.0)
           (m/mnorm (.z cc) -0.7484710611517463 0.651464411656511 0.0 255.0)
           (.w cc))))

(defn from-IPT
  "IPT -> RGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        ^Vec3 LMS' (-> (Vec3. (+ (.x c) (* 0.0975689305146139 (.y c)) (* 0.2052264331645916 (.z c)))
                              (+ (.x c) (* -0.1138764854731471 (.y c)) (* 0.13321715836999806 (.z c)))
                              (+ (.x c) (* 0.0326151099170664 (.y c)) (* -0.6768871830691793 (.z c))))
                       (v/fmap spow-r043))]
    (from-XYZ1 (Vec4. (+ (* 1.8502429449432056 (.x LMS'))
                         (* -1.1383016378672328 (.y LMS'))
                         (* 0.23843495850870136 (.z LMS')))
                      (+ (* 0.3668307751713486 (.x LMS'))
                         (* 0.6438845448402355 (.y LMS'))
                         (* -0.010673443584379992 (.z LMS')))
                      (* 1.088850174216028 (.z LMS'))
                      (.w c)))))

(defn from-IPT*
  "IPT -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-IPT (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.99998787116167)
                     (m/mnorm (.y c) 0.0 255.0 -0.45339260927924635 0.662432531162485)
                     (m/mnorm (.z c) 0.0 255.0 -0.7484710611517463 0.651464411656511)
                     (.w c)))))

;; IgPgTg
;; https://www.ingentaconnect.com/content/ist/jpi/2020/00000003/00000002/art00002#

(defn- spow-0427
  ^double [^double v]
  (spow v 0.427))

(defn- spow-r0427
  ^double [^double v]
  (spow v 2.34192037470726))

(defn to-IgPgTg
  "RGB -> IgPgTg

  Ranges:

  * Ig: 0.0 - 0.97
  * Pg: -0.35 - 0.39
  * Tg: -0.41 - 0.44"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [c (to-XYZ1 c)
        ^Vec3 LMS (-> (Vec3. (/ (+ (* 2.968 (.x c)) (* 2.741 (.y c)) (* -0.649 (.z c))) 18.36)
                             (/ (+ (* 1.237 (.x c)) (* 5.969 (.y c)) (* -0.173 (.z c))) 21.46)
                             (/ (+ (* -0.318 (.x c)) (* 0.387 (.y c)) (* 2.311 (.z c))) 19435.0))
                      (v/fmap spow-0427))]
    (Vec4. (+ (* 0.117 (.x LMS)) (* 1.464 (.y LMS)) (* 0.13 (.z LMS)))
           (+ (* 8.285 (.x LMS)) (* -8.361 (.y LMS)) (* 21.4 (.z LMS)))
           (+ (* -1.208 (.x LMS)) (* 2.412 (.y LMS)) (* -36.53 (.z LMS)))
           (.w c))))

(defn to-IgPgTg*
  "RGB -> IgPgTg*, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-IgPgTg c)]
    (Vec4. (m/mnorm (.x cc) 0.0 0.974152505851159 0.0 255.0)
           (m/mnorm (.y cc) -0.3538247140103022 0.393984191390090 0.0 255.0)
           (m/mnorm (.z cc) -0.41178992005867743 0.43676627200707374 0.0 255.0)
           (.w cc))))

(def ^:private igpgtg-vec3 (Vec3. 18.36 21.46 19435.0))

(defn from-IgPgTg
  "IgPgTg -> RGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        ^Vec3 LMS' (-> (Vec3. (+ (* 0.5818464618992453 (.x c))
                                 (* 0.12331854793907805 (.y c))
                                 (* 0.07431308420320755 (.z c)))
                              (+ (* 0.6345481937914152 (.x c))
                                 (* -0.009437923746683726 (.y c))
                                 (* -0.0032707446752298776 (.z c)))
                              (+ (* 0.022656986516578295 (.x c))
                                 (* -0.004701151874826374 (.y c))
                                 (* -0.030048158824914566 (.z c))))
                       (v/fmap spow-r0427)
                       (v/emult igpgtg-vec3))]
    (from-XYZ1 (Vec4. (+ (* 0.4343486855574634 (.x LMS'))
                         (* -0.2063623701142843 (.y LMS'))
                         (* 0.1065303361735277 (.z LMS')))
                      (+ (* -0.08785463778363382 (.x LMS'))
                         (* 0.2084634664799235 (.y LMS'))
                         (* -0.009066845616854849 (.z LMS')))
                      (+ (* 0.07447971736457794 (.x LMS'))
                         (* -0.06330532030466153 (.y LMS'))
                         (* 0.4488903142176134 (.z LMS')))
                      (.w c)))))

(defn from-IgPgTg*
  "IgPgTg -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-IgPgTg (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 0.974152505851159)
                        (m/mnorm (.y c) 0.0 255.0 -0.3538247140103022 0.393984191390090)
                        (m/mnorm (.z c) 0.0 255.0 -0.41178992005867743 0.43676627200707374)
                        (.w c)))))

;; Jab https://www.osapublishing.org/oe/abstract.cfm?uri=oe-25-13-15131

(def ^{:private true :const true :tag 'double} jab-b 1.15)
(def ^{:private true :const true :tag 'double} jab-g 0.66)
(def ^{:private true :const true :tag 'double} jab-rb (/ jab-b))
(def ^{:private true :const true :tag 'double} jab-rg (/ jab-g))
(def ^{:private true :const true :tag 'double} jab-b- (dec jab-b))
(def ^{:private true :const true :tag 'double} jab-g- (dec jab-g))
(def ^{:private true :const true :tag 'double} jab-c1 (/ 3424.0 (m/fpow 2.0 12)))
(def ^{:private true :const true :tag 'double} jab-c2 (/ 2413.0 (m/fpow 2.0 7)))
(def ^{:private true :const true :tag 'double} jab-c3 (/ 2392.0 (m/fpow 2.0 7)))
(def ^{:private true :const true :tag 'double} jab-n (/ 2610.0 (m/fpow 2.0 14)))
(def ^{:private true :const true :tag 'double} jab-p (* 1.7 (/ 2523.0 (m/fpow 2.0 5))))
(def ^{:private true :const true :tag 'double} jab-rn (/ jab-n))
(def ^{:private true :const true :tag 'double} jab-rp (/ jab-p))
(def ^{:private true :const true :tag 'double} jab-d -0.56)
(def ^{:private true :const true :tag 'double} jab-d+ (inc jab-d))
(def ^{:private true :const true :tag 'double} jab-d0 1.6295499532821566e-11)

(defn- jab-lms->lms' 
  ^double [^double v]
  (let [v (m/pow (/ v 10000.0) jab-n)]
    (m/pow (/ (+ jab-c1 (* jab-c2 v))
              (inc (* jab-c3 v))) jab-p)))

(defn to-JAB
  "RGB -> JzAzBz

  Jab https://www.osapublishing.org/oe/abstract.cfm?uri=oe-25-13-15131

  Ranges:

  * J: 0.0 - 0.17
  * a: -0.09 - 0.11
  * b: -0.156 - 0.115
  
  Reference white point set to 100.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [c (to-XYZ c)
        X' (- (* jab-b (.x c)) (* jab-b- (.z c)))
        Y' (- (* jab-g (.y c)) (* jab-g- (.x c)))
        ^Vec3 LMS' (-> (Vec3. (+ (* 0.41478972 X') (* 0.579999 Y') (* 0.0146480 (.z c)))
                              (+ (* -0.2015100 X') (* 1.120649 Y') (* 0.0531008 (.z c)))
                              (+ (* -0.0166008 X') (* 0.264800 Y') (* 0.6684799 (.z c))))
                       (v/fmap jab-lms->lms'))
        ^Vec3 Iab (Vec3. (+ (* 0.5 (.x LMS')) (* 0.5 (.y LMS')))
                         (+ (* 3.524000 (.x LMS')) (* -4.066708 (.y LMS')) (* 0.542708 (.z LMS')))
                         (+ (* 0.199076 (.x LMS')) (* 1.096799 (.y LMS')) (* -1.295875 (.z LMS'))))]
    (Vec4. (- (/ (* jab-d+ (.x Iab))
                 (inc (* jab-d (.x Iab)))) jab-d0) (.y Iab) (.z Iab) (.w c))))

(defn to-JAB*
  "RGB -> JzAzBz, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-JAB c)]
    (Vec4. (m/mnorm (.x cc) -3.2311742677852644E-26 0.16717463103478347 0.0 255.0)
           (m/mnorm (.y cc) -0.09286319310837648 0.1090265140291988 0.0 255.0)
           (m/mnorm (.z cc) -0.15632173559361429 0.11523306877502998 0.0 255.0)
           (.w cc))))

(defn- jab-lms'->lms 
  ^double [^double v]
  (let [v (m/pow v jab-rp)]
    (* 10000.0 (m/pow (/ (- jab-c1 v)
                         (- (* jab-c3 v) jab-c2)) jab-rn))))

(defn from-JAB
  "JzAzBz -> RGB

  For ranges, see [[to-JAB]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        J+ (+ jab-d0 (.x c))
        I (/ J+ (- jab-d+ (* jab-d J+)))
        ^Vec3 LMS (-> (Vec3. (+ (* 1.0000000000000002 I) (* 0.1386050432715393 (.y c)) (* 0.05804731615611886 (.z c)))
                             (+ (* 0.9999999999999999 I) (* -0.1386050432715393 (.y c)) (* -0.05804731615611886 (.z c)))
                             (+ (* 0.9999999999999998 I) (* -0.09601924202631895 (.y c)) (* -0.8118918960560388 (.z c))))
                      (v/fmap jab-lms'->lms))
        ^Vec3 XYZ' (Vec3. (+ (* 1.9242264357876069 (.x LMS)) (* -1.0047923125953657 (.y LMS)) (* 0.037651404030617994 (.z LMS)))
                          (+ (* 0.350316762094999 (.x LMS)) (* 0.7264811939316552 (.y LMS)) (* -0.06538442294808501 (.z LMS)))
                          (+ (* -0.09098281098284752 (.x LMS)) (* -0.3127282905230739 (.y LMS)) (* 1.5227665613052603 (.z LMS))))
        X (* jab-rb (+ (.x XYZ') (* jab-b- (.z XYZ'))))]
    (from-XYZ (Vec4. X
                     (* jab-rg (+ (.y XYZ') (* jab-g- X)))
                     (.z XYZ') (.w c)))))

(defn from-JAB*
  "JzAzBz -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-JAB (Vec4. (m/mnorm (.x c) 0.0 255.0 -3.2311742677852644E-26 0.16717463103478347)
                     (m/mnorm (.y c) 0.0 255.0 -0.09286319310837648 0.1090265140291988)
                     (m/mnorm (.z c) 0.0 255.0 -0.15632173559361429 0.11523306877502998)
                     (.w c)))))

;;

(defn to-JCH
  "RGB -> JCH

  Hue based color space derived from JAB
  
  Ranges:

  * J: 0.0 - 0.167
  * C: 0.0 - 0.159
  * H: 0.0 - 360.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (to-luma-color-hue to-JAB c))

(defn to-JCH*
  "RGB -> JCH, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-JCH c)]
    (Vec4. (m/mnorm (.x cc) -3.2311742677852644E-26, 0.16717463103478347 0.0 255.0)
           (m/mnorm (.y cc) 1.2924697071141057E-26, 0.15934590856406236 0.0 255.0)
           (m/mnorm (.z cc) 1.0921476445810189E-5, 359.99995671898046 0.0 255.0)
           (.w cc))))

(defn from-JCH
  "JCH -> RGB

  For ranges, see [[to-JCH]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (from-luma-color-hue from-JAB c))

(defn from-JCH*
  "JCH -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-JCH (Vec4. (m/mnorm (.x c) 0.0 255.0 -3.2311742677852644E-26, 0.16717463103478347)
                     (m/mnorm (.y c) 0.0 255.0 1.2924697071141057E-26, 0.15934590856406236)
                     (m/mnorm (.z c) 0.0 255.0 1.0921476445810189E-5, 359.99995671898046)
                     (.w c)))))


;; Hue based

(defn- to-HC
  "Calculate hue and chroma"
  ^Vec4 [^Vec4 c]
  (let [M (max (.x c) (.y c) (.z c))
        m (min (.x c) (.y c) (.z c))
        C (- M m)
        ^double h (if (zero? C)
                    0.0
                    (let [rC (/ C)]
                      (cond
                        (== M (.x c)) (mod (* rC (- (.y c) (.z c))) 6.0)
                        (== M (.y c)) (+ 2.0 (* rC (- (.z c) (.x c))))
                        :else (+ 4.0 (* rC (- (.x c) (.y c)))))))]
    (Vec4. (* 60.0 h) C M m)))

(defn- from-HCX
  "Convert HCX to RGB"
  ^Vec3 [^double h ^double c ^double x]
  (cond
    (<= 0.0 h 1.0) (Vec3. c x 0.0)
    (<= 1.0 h 2.0) (Vec3. x c 0.0)
    (<= 2.0 h 3.0) (Vec3. 0.0 c x)
    (<= 3.0 h 4.0) (Vec3. 0.0 x c)
    (<= 4.0 h 5.0) (Vec3. x 0.0 c)
    :else (Vec3. c 0.0 x)))

(def ^{:private true :const true :tag 'double} n360->255 (/ 255.0 360.0))
(def ^{:private true :const true :tag 'double} n255->360 (/ 360.0 255.0))

(defn- normalize-HSx
  "Make output range 0-255"
  ^Vec4 [^Vec4 c]
  (Vec4. (* n360->255 (.x c))
         (* 255.0 (.y c))
         (* 255.0 (.z c))
         (.w c)))

(defn- denormalize-HSx 
  "Make output range native to HSx colorspaces"
  ^Vec4 [^Vec4 c]
  (Vec4. (* n255->360 (.x c))
         (/ (.y c) 255.0)
         (/ (.z c) 255.0)
         (.w c)))

(defn- wrap-hue 
  "Wrap hue to enable interpolations"
  ^double [^double h]
  (m/wrap 0.0 360.0 h))

;; HSI

(defn to-HSI
  "RGB -> HSI

  Ranges:

  * H: 0.0 - 360
  * S: 0.0 - 1.0
  * I: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        hc (to-HC c)
        I (/ (+ (.x c) (.y c) (.z c)) 3.0)
        S (if (zero? I) 0.0
              (- 1.0 (/ (.w hc) I)))]
    (Vec4. (.x hc) S (/ I 255.0) (.w c))))

(defn from-HSI
  "HSI -> RGB

  For ranges, see [[to-HSI]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        h' (/ (wrap-hue (.x c)) 60.0)
        z (- 1.0 (m/abs (dec (mod h' 2.0))))
        C (/ (* 3.0 (.z c) (.y c)) (inc z))
        X (* C z)
        m (* (.z c) (- 1.0 (.y c)))
        rgb' (v/add (from-HCX h' C X) (Vec3. m m m))]
    (v/vec4 (v/mult rgb' 255.0) (.w c))))

(def ^{:metadoc/categories meta-conv :doc "RGB -> HSI, normalized"} to-HSI* (comp normalize-HSx to-HSI))
(def ^{:metadoc/categories meta-conv :doc "HSI -> RGB, normalized"} from-HSI* (comp from-HSI denormalize-HSx pr/to-color))

;; HSV

(defn to-HSV
  "RGB -> HSV

    Ranges:

  * H: 0.0 - 360
  * S: 0.0 - 1.0
  * V: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        hc (to-HC c)
        V (.z hc)
        S (if (zero? V) 0.0
              (/ (.y hc) V))]
    (Vec4. (.x hc) S (/ V 255.0) (.w c))))

(defn from-HSV
  "HSV -> RGB

  Same as HSB.
  
  For ranges, see [[to-HSV]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        C (* (.y c) (.z c))
        h' (/ (wrap-hue (.x c)) 60.0)
        X (* C (- 1.0 (m/abs (dec (mod h' 2.0)))))
        m (- (.z c) C)
        ^Vec3 rgb' (v/add (from-HCX h' C X) (Vec3. m m m))]
    (v/vec4 (v/mult rgb' 255.0) (.w c))))

(def ^{:metadoc/categories meta-conv :doc "RGB -> HSV, normalized"} to-HSV* (comp normalize-HSx to-HSV))
(def ^{:metadoc/categories meta-conv :doc "HSV -> RGB, normalized"} from-HSV* (comp from-HSV denormalize-HSx pr/to-color))

;; HSB = HSV

(def ^{:metadoc/categories meta-conv :doc "RGB -> HSB(V), normalized (see [[to-HSV]])"} to-HSB to-HSV)
(def ^{:metadoc/categories meta-conv :doc "HSB(V) -> RGB, normalized (see [[from-HSV]])"} from-HSB from-HSV)
(def ^{:metadoc/categories meta-conv :doc "RGB -> HSB(V) (see [[to-HSV*]])"} to-HSB* to-HSV*)
(def ^{:metadoc/categories meta-conv :doc "HSB(V) -> RGB (see [[from-HSV*]])"} from-HSB* from-HSV*)

;; paletton HSV

(declare paletton-hsv-to-rgb)
(declare paletton-rgb-to-hsv)

(defn to-PalettonHSV
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (paletton-rgb-to-hsv (.x c) (.y c) (.z c) (.w c))))

(defn to-PalettonHSV*
  ^Vec4 [c]
  (let [^Vec4 c (to-PalettonHSV c)]
    (Vec4. (* n360->255 (.x c))
           (* 127.5 (.y c))
           (* 127.5 (.z c))
           (.w c))))

(defn from-PalettonHSV
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (paletton-hsv-to-rgb (.x c) (.y c) (.z c) (.w c))))

(defn from-PalettonHSV*
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-PalettonHSV (Vec4. (* n255->360 (.x c))
                             (/ (.y c) 127.5)
                             (/ (.z c) 127.5)
                             (.w c)))))

;; HSL


(defn to-HSL
  "RGB -> HSL

  Ranges:

  * H: 0.0 - 360
  * S: 0.0 - 1.0
  * L: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        hc (to-HC c)
        L (/ (* 0.5 (+ (.z hc) (.w hc))) 255.0)
        S (if (or (== 1.0 L)
                  (zero? (.y hc))) 0.0
              (/ (.y hc) (- 1.0 (m/abs (dec (+ L L))))))]
    (Vec4. (.x hc) (/ S 255.0) L (.w c))))

(defn from-HSL
  "HSL -> RGB

  For ranges, see [[to-HSL]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        C (* (.y c) (- 1.0 (m/abs (dec (+ (.z c) (.z c))))))
        h' (/ (wrap-hue (.x c)) 60.0)
        X (* C (- 1.0 (m/abs (dec (mod h' 2.0)))))
        m (- (.z c) (* 0.5 C))
        ^Vec3 rgb' (v/add (from-HCX h' C X) (Vec3. m m m))]
    (v/vec4 (v/mult rgb' 255.0) (.w c))))

(def ^{:metadoc/categories meta-conv :doc "RGB -> HSL, normalized"} to-HSL* (comp normalize-HSx to-HSL))
(def ^{:metadoc/categories meta-conv :doc "HSL -> RGB, normalized"} from-HSL* (comp from-HSL denormalize-HSx pr/to-color))

;; HCL
;; http://w3.uqo.ca/missaoui/Publications/TRColorSpace.zip

(defn to-HCL
  "RGB -> HCL, by Sarifuddin and Missaou.

  lambda = 3.0
  
  Returned ranges:

  * H: -180.0 - 180.0
  * C: 0.0 - 170.0
  * L: 0.0 - 135.266"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        r (.x c)
        g (.y c)
        b (.z c)
        mn (min r g b)
        mx (max r g b)
        Q (m/exp (if (zero? mx) 0.0 (* 0.03 (/ mn mx))))
        L (* 0.5 (+ (* Q mx) (* (dec Q) mn)))
        gb- (- g b)
        rg- (- r g)
        C (* (/ Q 3.0) (+ (m/abs rg-)
                          (m/abs gb-)
                          (m/abs (- b r))))
        H (m/degrees (if (zero? gb-) 0.0 (m/atan (/ gb- rg-))))
        H (cond
            (and (>= rg- 0.0) (>= gb- 0,0)) (* H m/TWO_THIRD)
            (and (>= rg- 0.0) (neg? gb-)) (* 2.0 m/TWO_THIRD H)
            (and (neg? rg-) (>= gb- 0,0)) (+ (* 2.0 m/TWO_THIRD H) 180.0)
            (and (neg? rg-) (neg? gb-)) (- (* m/TWO_THIRD H) 180.0) 
            :else H)]
    (Vec4. H C L (.w c))))

(defn from-HCL
  "HCL -> RGB, by Sarifuddin and Missaou.

  For accepted ranges, see [[to-HCL]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        H       (m/wrap -180.0 180.0 (.x c))
        C       (* 3.0 (.y c))
        L       (* 4.0 (.z c))
        Q       (* 2.0 (m/exp (* 0.03 (- 1.0 (/ C L)))))
        mn      (/ (- L C) (* 2.0 (dec Q)))
        mx      (+ mn (/ C Q))]
    (cond
      (<= 0.0 H 60.0)      (let [t (m/tan (m/radians (* 1.5 H)))]
                             (Vec4. mx (/ (+ (* mx t) mn) (inc t)) mn (.w c)))
      (<= 60.0 H 120.0)    (let [t (m/tan (m/radians (* 0.75 (- H 180.0))))]
                             (Vec4. (/ (- (* mx (inc t)) mn) t) mx mn (.w c)))
      (<= 120.0 H 180.0)   (let [t (m/tan (m/radians (* 0.75 (- H 180.0))))]
                             (Vec4. mn mx (- (* mx (inc t)) (* mn t)) (.w c)))
      (<= -60.0 H 0.0)     (let [t (m/tan (m/radians (* 0.75 H)))]
                             (Vec4. mx mn (- (* mn (inc t)) (* mx t)) (.w c)))
      (<= -120.0 H -60.0)  (let [t (m/tan (m/radians (* 0.75 H)))]
                             (Vec4. (/ (- (* mn (inc t)) mx) t) mn mx (.w c)))
      :else (let [t (m/tan (m/radians (* 1.5 (+ H 180.0))))]
              (Vec4. mn (/ (+ (* mn t) mx) (inc t)) mx (.w c))))))

(defn to-HCL*
  "RGB -> HCL, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c] 
  (let [cc (to-HCL c)]
    (Vec4. (m/mnorm (.x cc) -179.8496181535773 180.0 0.0 255.0)
           (* 255.0 (/ (.y cc) 170.0))
           (* 255.0 (/ (.z cc) 135.26590615814683))
           (.w cc))))

(defn from-HCL*
  "HCL -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-HCL (Vec4. (m/mnorm (.x c) 0.0 255.0 -179.8496181535773 180.0)
                     (* 170.0 (/ (.y c) 255.0))
                     (* 135.26590615814683 (/ (.z c) 255.0))
                     (.w c)))))

;; ### HWB

;; HWB - A More Intuitive Hue-Based Color Model
;; by Alvy Ray Smitch and Eric Ray Lyons, 1995-1996

(defn to-HWB
  "RGB -> HWB

  HWB - A More Intuitive Hue-Based Color Model
  by Alvy Ray Smitch and Eric Ray Lyons, 1995-1996

  Ranges:

  * H: 0.0 - 360.0
  * W: 0.0 - 1.0
  * B: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (to-HSV c)]
    (Vec4. (.x c) (* (- 1.0 (.y c)) (.z c)) (- 1.0 (.z c)) (.w c))))

(defn from-HWB
  "HWB -> RGB

  For ranges, see [[to-HWB]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        w (.y c)
        b (.z c)
        w+b (+ w b)
        w (if (> w+b 1.0) (/ w w+b) w)
        b- (if (> w+b 1.0) (- 1.0 (/ b w+b)) (- 1.0 b))]
    (from-HSV (Vec4. (.x c) (if (zero? w) 1.0 (- 1.0 (/ w b-))) b- (.w c)))))

(def ^{:metadoc/categories meta-conv :doc "RGB -> HWB, normalized"} to-HWB* (comp normalize-HSx to-HWB))
(def ^{:metadoc/categories meta-conv :doc "HWB -> RGB, normalized"} from-HWB* (comp from-HWB denormalize-HSx pr/to-color))


;; ### GLHS
;;
;; Color Theory and Modeling for Computer Graphics, Visualization, and Multimedia Applications (The Springer International Series in Engineering and Computer Science) by Haim Levkowitz

;; Page 79, minimizer
(def ^{:private true :const true :tag 'double} weight-max 0.7)
(def ^{:private true :const true :tag 'double} weight-mid 0.1)
(def ^{:private true :const true :tag 'double} weight-min 0.2)

(defn to-GLHS
  "RGB -> GLHS

  Color Theory and Modeling for Computer Graphics, Visualization, and Multimedia Applications (The Springer International Series in Engineering and Computer Science) by Haim Levkowitz

  Weights: 0.2 (min), 0.1 (mid), 0.7 (max).

  Ranges:
  
  * L: 0.0 - 1.0
  * H: 0.0 - 360.0
  * S: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        mx (max (.x c) (.y c) (.z c))
        md (stat/median-3 (.x c) (.y c) (.z c))
        mn (min (.x c) (.y c) (.z c))]
    (if (== mx mn)
      (Vec4. (/ mx 255.0) 0 0 (.w c))
      (let [l (+ (* weight-max mx) (* weight-mid md) (* weight-min mn))
            r (/ (- mx mn))
            e (* (- md mn) r)
            ^long k (cond
                      (and (> (.x c) (.y c)) (>= (.y c) (.z c))) 0
                      (and (>= (.y c) (.x c)) (> (.x c) (.z c))) 1
                      (and (> (.y c) (.z c)) (>= (.z c) (.x c))) 2
                      (and (>= (.z c) (.y c)) (> (.y c) (.x c))) 3
                      (and (> (.z c) (.x c)) (>= (.x c) (.y c))) 4
                      :else 5)
            f (if (even? k)
                e
                (* (- mx md) r))
            h (* 60.0 (+ k f))
            lq (* 255.0 (+ (* weight-mid e) weight-max))
            s (if (<= l lq)
                (/ (- l mn) l)
                (/ (- mx l) (- 255.0 l)))]
        (Vec4. (/ l 255.0) h s (.w c))))))

(defn to-GLHS*
  "RGB -> GLHS, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c] 
  (let [cc (to-GLHS c)]
    (Vec4. (* 255.0 (.x cc))
           (m/mnorm (.y cc) 0.0 359.7647058823529 0.0 255.0)
           (* 255.0 (.z cc))
           (.w cc))))

(defn from-GLHS
  "GLHS -> RGB

  For ranges, see [[to-GLHS]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        l (* 255.0 (.x c))]
    (if (zero? (.z c))
      (Vec4. l l l (.w c))
      (let [h (/ (wrap-hue (.y c)) 60.0)
            k (long (m/floor h))
            f (- h k)
            fp (if (even? k) f (- 1.0 f))
            wfw (+ (* weight-mid fp) weight-max)
            lq (* 255.0 wfw)
            s (.z c)
            ^Vec3 rgb (if (<= l lq)
                        (let [mn (* (- 1.0 s) l)
                              md (/ (+ (* fp l) (* mn (- (* (- 1.0 fp) weight-max) (* fp weight-min)))) wfw)
                              mx (/ (- (- l (* md weight-mid)) (* mn weight-min)) weight-max)]
                          (Vec3. mn md mx))
                        (let [mx (+ (* s 255.0) (* (- 1.0 s) l))
                              md (/ (- (* (- 1.0 fp) l) (* mx (- (* (- 1.0 fp) weight-max) (* fp weight-min))))
                                    (+ (* (- 1.0 fp) weight-mid) weight-min))
                              mn (/ (- (- l (* mx weight-max)) (* md weight-mid)) weight-min)]
                          (Vec3. mn md mx)))]
        (case k
          0 (Vec4. (.z rgb) (.y rgb) (.x rgb) (.w c))
          1 (Vec4. (.y rgb) (.z rgb) (.x rgb) (.w c))
          2 (Vec4. (.x rgb) (.z rgb) (.y rgb) (.w c))
          3 (Vec4. (.x rgb) (.y rgb) (.z rgb) (.w c))
          4 (Vec4. (.y rgb) (.x rgb) (.z rgb) (.w c))
          5 (Vec4. (.z rgb) (.x rgb) (.y rgb) (.w c))
          6 (Vec4. (.z rgb) (.y rgb) (.x rgb) (.w c)))))))

(defn from-GLHS*
  "GLHS -> RGB"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-GLHS (Vec4. (/ (.x c) 255.0)
                      (m/mnorm (.y c) 0.0 255.0 0.0 359.7647058823529)
                      (/ (.z c) 255.0)
                      (.w c)))))

;; ### YPbPr

(defn to-YPbPr
  "RGB -> YPbPr

  Ranges:

  * Y: 0.0 - 255.0
  * Pb: -236.6 - 236.6
  * Pr: -200.8 - 200.8"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        y (+ (* 0.2126 (.x c))
             (* 0.7152 (.y c))
             (* 0.0722 (.z c)))
        pb (- (.z c) y)
        pr (- (.x c) y)]
    (Vec4. y pb pr (.w c))))

(defn to-YPbPr*
  "RGB -> YPbPr, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-YPbPr c)]
    (Vec4. (.x cc)
           (m/mnorm (.y cc) -236.589 236.589 0.0 255.0)
           (m/mnorm (.z cc) -200.787 200.787 0.0 255.0)
           (.w cc))))

(defn from-YPbPr
  "YPbPr -> RGB

  For ranges, see [[to-YPbPr]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        b (+ (.x c) (.y c))
        r (+ (.x c) (.z c))
        g (/ (- (.x c) (* 0.2126 r) (* 0.0722 b)) 0.7152)]
    (Vec4. r g b (.w c))))

(defn from-YPbPr*
  "YPbPr -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-YPbPr (Vec4. (.x c)
                       (m/mnorm (.y c) 0.0 255.0 -236.589 236.589)
                       (m/mnorm (.z c) 0.0 255.0 -200.787 200.787)
                       (.w c)))))

;; ### YDbDr

(defn to-YDbDr
  "RGB -> YDbDr

  Ranges:

  * Y: 0.0 - 255.0
  * Db: -340.0 - 340.0
  * Dr: -340.0 - 340.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c] 
  (let [^Vec4 c (pr/to-color c)
        Y (+ (* 0.299 (.x c)) (* 0.587 (.y c)) (* 0.114 (.z c)))
        Db (+ (* -0.45 (.x c)) (* -0.883 (.y c)) (* 1.333 (.z c)))
        Dr (+ (* -1.333 (.x c)) (* 1.116 (.y c)) (* 0.217 (.z c)))]
    (Vec4. Y Db Dr (.w c))))

(defn to-YDbDr*
  "RGB -> YDbDr"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-YDbDr c)]
    (Vec4. (.x cc)
           (m/mnorm (.y cc) -339.91499999999996 339.91499999999996 0.0 255.0)
           (m/mnorm (.z cc) -339.91499999999996 339.915 0.0 255.0)
           (.w cc))))

(defn from-YDbDr
  "YDbDr -> RGB

  For ranges, see [[to-YDbDr]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Y (.x c)
        Db (.y c)
        Dr (.z c)
        r (+ Y (* 9.2303716147657e-05 Db) (* -0.52591263066186533 Dr))
        g (+ Y (* -0.12913289889050927 Db) (* 0.26789932820759876 Dr))
        b (+ Y (* 0.66467905997895482 Db) (* -7.9202543533108e-05 Dr))]
    (Vec4. r g b (.w c))))

(defn from-YDbDr*
  "YDbDr -> RGB, normalized"
  {:metadoc/categories meta-conv}
  [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-YDbDr (Vec4. (.x c)
                       (m/mnorm (.y c) 0.0 255.0 -339.91499999999996 339.91499999999996)
                       (m/mnorm (.z c) 0.0 255.0 -339.91499999999996 339.915)
                       (.w c)))))


;; ### YCbCr

;; JPEG version

(def ^:private ^:const y-norm ohta-s)

(defn to-YCbCr
  "RGB -> YCbCr

  Used in JPEG. BT-601

  Ranges;
  
  * Y: 0.0 - 255.0
  * Cb: -127.5 - 127.5
  * Cr: -127.5 - 127.5"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Y (+ (* 0.298839 (.x c)) (* 0.586811 (.y c)) (* 0.114350 (.z c)))
        Cb (+ (* -0.168736 (.x c)) (* -0.331264 (.y c)) (* 0.5 (.z c)))
        Cr (+ (* 0.5 (.x c)) (* -0.418688 (.y c)) (* -0.081312 (.z c)))]
    (Vec4. Y Cb Cr (.w c))))

(defn to-YCbCr*
  "RGB -> YCbCr, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (v/add (to-YCbCr c) y-norm))

(defn from-YCbCr
  "YCbCr -> RGB

  For ranges, see [[to-YCbCr]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Cb (.y c)
        Cr (.z c)
        r (+ (* 0.99999999999914679361 (.x c)) (* -1.2188941887145875e-06 Cb) (* 1.4019995886561440468 Cr))
        g (+ (* 0.99999975910502514331 (.x c)) (* -0.34413567816504303521 Cb) (* -0.71413649331646789076 Cr))
        b (+ (* 1.00000124040004623180 (.x c)) (* 1.77200006607230409200 Cb) (* 2.1453384174593273e-06 Cr))]
    (Vec4. r g b (.w c))))

(defn from-YCbCr*
  "YCbCr -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (from-YCbCr (v/sub (pr/to-color c) y-norm)))

;; ### YUV

(defn to-YUV
  "RGB -> YUV

  Ranges:

  * Y: 0.0 - 255.0
  * u: -111.2 - 111.2
  * v: -156.8 - 156.8"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (Vec4. (+ (* 0.298839 (.x c)) (* 0.586811 (.y c)) (* 0.114350 (.z c)))
           (+ (* -0.147 (.x c)) (* -0.289 (.y c)) (* 0.436 (.z c)))
           (+ (* 0.615 (.x c)) (* -0.515 (.y c)) (* -0.1 (.z c)))
           (.w c))))

(defn to-YUV*
  "RGB -> YUV, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-YUV c)]
    (Vec4. (.x cc)
           (m/mnorm (.y cc) -111.17999999999999 111.17999999999999 0.0 255.0)
           (m/mnorm (.z cc) -156.82500000000002 156.825 0.0 255.0)
           (.w cc))))

(defn from-YUV
  "YUV -> RGB

  For ranges, see [[to-YUV]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Y (.x c)
        U (.y c)
        V (.z c)
        r (+ Y (* -3.945707070708279e-05 U) (* 1.1398279671717170825 V))
        g (+ Y (* -0.3946101641414141437 U) (* -0.5805003156565656797 V))
        b (+ Y (* 2.0319996843434342537 U) (* -4.813762626262513e-04 V))]
    (Vec4. r g b (.w c))))

(defn from-YUV*
  "YUV -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-YUV (Vec4. (.x c)
                     (m/mnorm (.y c) 0.0 255.0 -111.17999999999999 111.17999999999999)
                     (m/mnorm (.z c) 0.0 255.0 -156.82500000000002 156.825)
                     (.w c)))))

;; ### YIQ

(defn to-YIQ
  "RGB -> YIQ

  Ranges:

  * Y: 0.0 - 255.0
  * I: -151.9 - 151.9
  * Q: -133.26 - 133.26"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (Vec4. (+ (* 0.298839 (.x c)) (* 0.586811 (.y c)) (* 0.114350 (.z c)))
           (+ (* 0.595716 (.x c)) (* -0.274453 (.y c)) (* -0.321263 (.z c)))
           (+ (* 0.211456 (.x c)) (* -0.522591 (.y c)) (* 0.311135 (.z c)))
           (.w c))))

(defn to-YIQ*
  "RGB -> YIQ, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-YIQ c)]
    (Vec4. (.x cc)
           (m/mnorm (.y cc) -151.90758 151.90758 0.0 255.0)
           (m/mnorm (.z cc) -133.260705 133.260705 0.0 255.0)
           (.w cc))))

(defn from-YIQ
  "YIQ -> RGB

  For ranges, see [[to-YIQ]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Y (.x c)
        I (.y c)
        Q (.z c)
        r (+ Y (* +0.9562957197589482261 I) (* 0.6210244164652610754 Q))
        g (+ Y (* -0.2721220993185104464 I) (* -0.6473805968256950427 Q))
        b (+ Y (* -1.1069890167364901945 I) (* 1.7046149983646481374 Q))]
    (Vec4. r g b (.w c))))

(defn from-YIQ*
  "YIQ -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        I (m/mnorm (.y c) 0.0 255.0 -151.90758 151.90758)
        Q (m/mnorm (.z c) 0.0 255.0 -133.260705 133.260705)]
    (from-YIQ (Vec4. (.x c) I Q (.w c)))))

;; ### YCgCo

(defn to-YCgCo
  "RGB -> YCgCo

  Ranges:
  
  * Y: 0.0 - 255.0
  * Cg: -127.5 - 127.5
  * Co: -127.5 - 127.5"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Y (+ (* 0.25 (.x c)) (* 0.5 (.y c)) (* 0.25 (.z c)))
        Cg (+ (* -0.25 (.x c)) (* 0.5 (.y c)) (* -0.25 (.z c)))
        Co (+ (* 0.5 (.x c)) (* -0.5 (.z c)))]
    (Vec4. Y Cg Co (.w c))))

(defn to-YCgCo*
  "RGB -> YCgCo, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (v/add (to-YCgCo c) y-norm))

(defn from-YCgCo
  "YCgCo -> RGB

  For ranges, see [[to-YCgCo]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        Cg (.y c)
        Co (.z c)
        tmp (- (.x c) Cg)]
    (Vec4. (+ Co tmp) (+ (.x c) Cg) (- tmp Co) (.w c))))

(defn from-YCgCo*
  "YCgCo -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (from-YCgCo (v/sub (pr/to-color c) y-norm)))

;; Cubehelix

(def ^{:private true :const true :tag 'double} ch-a -0.14861)
(def ^{:private true :const true :tag 'double} ch-b 1.78277)
(def ^{:private true :const true :tag 'double} ch-c -0.29227)
(def ^{:private true :const true :tag 'double} ch-d -0.90649)
(def ^{:private true :const true :tag 'double} ch-e 1.97294)
(def ^{:private true :const true :tag 'double} ch-ed (* ch-e ch-d))
(def ^{:private true :const true :tag 'double} ch-eb (* ch-e ch-b))
(def ^{:private true :const true :tag 'double} ch-bc-da (- (* ch-b ch-c) (* ch-d ch-a)))
(def ^{:private true :const true :tag 'double} ch-bc-da+ed-eb-r (/ (+ ch-bc-da ch-ed (- ch-eb))))

(defn to-Cubehelix
  "RGB -> Cubehelix

  D3 version
  
  Ranges:

  * H: 0.0 - 360.0
  * S: 0.0 - 4.61
  * L: 0.0 - 1.0"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        r (/ (.x c) 255.0)
        g (/ (.y c) 255.0)
        b (/ (.z c) 255.0)
        l (* ch-bc-da+ed-eb-r (+ (* ch-bc-da b) (* ch-ed r) (- (* ch-eb g))))
        bl (- b l)
        k (/ (- (* ch-e (- g l)) (* ch-c bl)) ch-d)
        s (/ (m/sqrt (+ (* k k) (* bl bl)))
             (* ch-e l (- 1.0 l)))]
    (if (Double/isNaN s)
      (Vec4. 0.0 0.0 l (.w c))
      (let [h (- (* (m/atan2 k bl) m/rad-in-deg) 120.0)]
        (Vec4. (if (neg? h) (+ h 360.0) h) s l (.w c))))))

(defn to-Cubehelix*
  "RGB -> Cubehelix, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [cc (to-Cubehelix c)]
    (Vec4. (m/mnorm (.x cc) 0.0 359.9932808311505 0.0 255.0)
           (m/mnorm (.y cc) 0.0 4.61438686803972 0.0 255.0)
           (* 255.0 (.z cc))
           (.w cc))))

(defn from-Cubehelix
  "Cubehelix -> RGB

  For ranges, see [[to-Cubehelix]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        h (* (+ (.x c) 120.0) m/deg-in-rad)
        l (.z c)
        a (* (.y c) l (- 1.0 l))
        cosh (m/cos h)
        sinh (m/sin h)]
    (Vec4. (* 255.0 (+ l (* a (+ (* ch-a cosh) (* ch-b sinh)))))
           (* 255.0 (+ l (* a (+ (* ch-c cosh) (* ch-d sinh)))))
           (* 255.0 (+ l (* a ch-e cosh)))
           (.w c))))

(defn from-Cubehelix*
  "Cubehelix -> RGB, normalized"
  {:metadoc/categories meta-conv}
  ^Vec4 [c] 
  (let [^Vec4 c (pr/to-color c)
        cc (Vec4. (m/mnorm (.x c) 0.0 255.0 0.0 359.9932808311505)
                  (m/mnorm (.y c) 0.0 255.0 0.0 4.61438686803972)
                  (/ (.z c) 255.0)
                  (.w c))]
    (from-Cubehelix cc)))

;; OSA
;; https://github.com/nschloe/colorio/blob/master/colorio/_osa.py

(defn to-OSA
  "OSA-UCS -> RGB

  https://github.com/nschloe/colorio/blob/master/colorio/_osa.py

  Ranges:

  * L: -13.5 - 7.14
  * j (around): -20.0 - 14.0 + some extreme values
  * g (around): -20.0 - 12.0 + some extreme values

  Note that for some cases (like `(to-OSA [18 7 4])`) function returns some extreme values."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [xyz (to-XYZ c)
        X (.x xyz)
        Y (.y xyz)
        Z (.z xyz)
        sum-xyz (+ X Y Z)
        x (if (zero? X) 0.0 (/ X sum-xyz))
        y (if (zero? Y) 0.0 (/ Y sum-xyz))
        K (+ (* 4.4934 x x)
             (* 4.3034 y y)
             (* -4.276 x y)
             (* -1.3744 x)
             (* -2.5643 y)
             1.8103)
        Y0 (* Y K)
        Y03 (- (m/cbrt Y0) m/TWO_THIRD)
        L' (* 5.9 (+ Y03 (* 0.042 (m/cbrt (- Y0 30.0)))))
        C (/ L' (* 5.9 Y03))
        R3 (m/cbrt (+ (* 0.7990 X) (* 0.4194 Y) (* -0.1648 Z)))
        G3 (m/cbrt (+ (* -0.4493 X) (* 1.3265 Y) (* 0.0927 Z)))
        B3 (m/cbrt (+ (* -0.1149 X) (* 0.3394 Y) (* 0.7170 Z)))
        a (+ (* -13.7 R3) (* 17.7 G3) (* -4.0 B3))
        b (+ (* 1.7 R3) (* 8.0 G3) (* -9.7 B3))
        L (/ (- L' 14.3993) m/SQRT2)]
    (Vec4. L (* C b) (* C a) (.w xyz))))

(def ^{:private true :const true :tag 'double} OSA-v (* 0.042 0.042 0.042))
(def ^{:private true :const true :tag 'double} OSA-30v (* 30.0 OSA-v))
(def ^{:private true :const true :tag 'double} OSA-a (- (inc OSA-v)))
(def ^{:private true :const true :tag 'double} OSA-detr (/ -139.68999999999997))
(def ^{:private true :const true :tag 'double} OSA-omega 4.957506551095124)

(deftype OSAFDF [^double fomega ^double dfomega ^double X ^double Y ^double Z])

(defn from-OSA
  "RGB -> OSA-UCS

  For ranges, see [[to-OSA]]."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 col (pr/to-color c)
        L (.x col)
        j(.y col)
        g (.z col)
        L' (+ (* m/SQRT2 L) 14.3993)
        u (+ (/ L' 5.9) m/TWO_THIRD)
        b (* 3.0 u)
        c (* -3.0 u u)
        d (+ (* u u u) OSA-30v)
        p (/ (- (* 3.0 OSA-a c)
                (* b b))
             (* 3.0 OSA-a OSA-a))
        aa27 (* 27.0 OSA-a OSA-a)
        q (/ (+ (* 2.0 b b b)
                (* -9.0 OSA-a b c)
                (* aa27 d))
             (* aa27 OSA-a))
        q2 (* 0.5 q)
        s (m/sqrt (+ (m/sq q2)
                     (m/pow3 (* p m/THIRD))))
        t (- (+ (m/cbrt (- s q2))
                (m/cbrt (- (- q2) s)))
             (/ b (* 3.0 OSA-a)))
        Y0 (* t t t)
        C (/ L' (* 5.9 (- t m/TWO_THIRD)))
        a (/ g C)
        b (/ j C)
        ap (* OSA-detr (+ (* -9.7 a) (* 4.0 b)))
        bp (* OSA-detr (+ (* -8.0 a) (* 17.7 b)))
        f-df (fn [^double omega]
               (let [cbrt-R omega
                     cbrt-G (+ omega ap)
                     cbrt-B (+ omega bp)
                     R (m/pow3 cbrt-R)
                     G (m/pow3 cbrt-G)
                     B (m/pow3 cbrt-B)
                     X (+ (* 1.06261827e+00 R) (* -4.12091749e-01 G) (* 2.97517985e-01 B))
                     Y (+ (* 3.59926645e-01 R) (* 6.40072108e-01 G) (* -2.61830489e-05 B))
                     Z (+ (* -8.96301459e-05 R) (* -3.69023452e-01 G) (* 1.44239010e+00 B))
                     sum-xyz (+ X Y Z)
                     x (if (zero? X) 0.0 (/ X sum-xyz))
                     y (if (zero? Y) 0.0 (/ Y sum-xyz))
                     K (+ (* 4.4934 x x)
                          (* 4.3034 y y)
                          (* -4.276 x y)
                          (* -1.3744 x)
                          (* -2.5643 y)
                          1.8103)
                     f (- (* Y K) Y0)
                     dR (* 3.0 (m/sq cbrt-R))
                     dG (* 3.0 (m/sq cbrt-G))
                     dB (* 3.0 (m/sq cbrt-B))
                     dX (+ (* 1.06261827e+00 dR) (* -4.12091749e-01 dG) (* 2.97517985e-01 dB))
                     dY (+ (* 3.59926645e-01 dR) (* 6.40072108e-01 dG) (* -2.61830489e-05 dB))
                     dZ (+ (* -8.96301459e-05 dR) (* -3.69023452e-01 dG) (* 1.44239010e+00 dB))
                     dsum-xyz (+ dX dY dZ)
                     dx (if (zero? X) 0.0 (/ (- (* dX sum-xyz) (* X dsum-xyz)) (* sum-xyz sum-xyz)))
                     dy (if (zero? Y) 0.0 (/ (- (* dY sum-xyz) (* Y dsum-xyz)) (* sum-xyz sum-xyz)))
                     dK (+ (* 4.4934 2.0 x dx)
                           (* 4.3034 2.0 y dy)
                           (* -4.276 (+ (* dx y) (* x dy)))
                           (* -1.3744 dx)
                           (* -2.5643 dy))
                     df (+ (* dY K) (* Y dK))]
                 (OSAFDF. f df X Y Z)))]
    (loop [iter (int 0)
           omega (double OSA-omega)]
      (let [^OSAFDF res (f-df omega)]
        (if (and (< iter 20)
                 (> (.fomega res) 1.0e-10))
          (recur (inc iter)
                 (- omega (/ (.fomega res)
                             (.dfomega res))))
          (from-XYZ (Vec4. (.X res) (.Y res) (.Z res) (.w col))))))))

(defn to-OSA*
  "OSA-UCS -> RGB, normalized

  Note that due to some extreme outliers, normalization is triple cubic root for `g` and `j`."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [c (to-OSA c)]
    (Vec4. (m/mnorm (.x c) -13.507581921540849 7.1379048733958435 0.0 255.0)
           (m/mnorm (m/cbrt (m/cbrt (m/cbrt (.y c)))) -1.4073863219389389 1.4228002556769352 0.0 255.0)
           (m/mnorm (m/cbrt (m/cbrt (m/cbrt (.z c)))) -1.4057783957453063 1.4175468647969818 0.0 255.0)
           (.w c))))

(defn from-OSA*
  "OSA-UCS -> RGB, normalized

  Note that due to some extreme outliers, normalization is triple cubic power for `g` and `j`."
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)]
    (from-OSA (Vec4. (m/mnorm (.x c) 0.0 255.0 -13.507581921540849 7.1379048733958435)
                     (m/pow3 (m/pow3 (m/pow3 (m/mnorm (.y c) 0.0 255.0 -1.4073863219389389 1.4228002556769352))))
                     (m/pow3 (m/pow3 (m/pow3 (m/mnorm (.z c) 0.0 255.0 -1.4057783957453063 1.4175468647969818))))
                     (.w c)))))

;; ### DIN99 family

(def ^:private din99-config
  {:din99 {:l99mult 105.509 :lmult 0.0158
           :cosc (m/cos (m/radians 16.0)) :sinc (m/sin (m/radians 16.0))
           :fmult 0.7 :c99mult 1.0 :gmult (/ 9.0 200.0) :hshift 0.0 :gdiv (/ 9.0 200.0)
           :r [0.0 100.0003116920774], :g [-27.45031251565246 36.17533184258057], :b [-33.395536343165965 31.1550705394938]}
   :din99b {:l99mult 303.67 :lmult 0.0039
            :cosc (m/cos (m/radians 26.0)) :sinc (m/sin (m/radians 26.0))
            :fmult 0.83 :c99mult 23.0 :gmult 0.075 :hshift (m/radians 26.0) :gdiv 1.0
            :r [0.0 99.99966889479346], :g [-40.110035967857236 45.52421609386709], :b [-40.48993353097326 44.366160432960626]}
   ;; there is no shift on Wikipedia, culori adds 26deg
   :din99o {:l99mult 303.67 :lmult 0.0039
            :cosc (m/cos (m/radians 26.0)) :sinc (m/sin (m/radians 26.0))
            :fmult 0.83 :c99mult 1.0 :gmult 0.075 :hshift 0.0 :gdiv 0.0435
            :r [0.0 99.99966889479346], :g [-35.434778333676974 48.87944227965091], :b [-50.296317246972684 45.92474100066964]}
   :din99c {:l99mult 317.65 :lmult 0.0037
            :cosc 1.0 :sinc 0.0
            :fmult 0.94 :c99mult 23.0 :gmult 0.066 :hshift 0.0 :gdiv 1.0
            :r [0.0 99.99963151018662], :g [-38.44209843435516 43.67298483923871], :b [-40.79039987931203 43.59390987464932]}
   :din99d {:l99mult 325.22 :lmult 0.0036
            :cosc (m/cos (m/radians 50.0)) :sinc (m/sin (m/radians 50.0))
            :fmult 1.14 :c99mult 22.5 :gmult 0.06 :hshift (m/radians 50.0) :gdiv 1.0
            :r [0.0 100.0001740520318], :g [-37.35102456489855 42.32332072914751], :b [-41.036070820486316 43.037583796265324]}})

(defn- make-to-DIN99
  [n]
  (let [{:keys [^double cosc ^double sinc ^double fmult ^double hshift ^double c99mult
                ^double gmult ^double gdiv ^double l99mult ^double lmult]} (din99-config n)]
    (fn ^Vec4 [c]
      (let [^Vec4 c (to-LAB c)
            e (+ (* cosc (.y c)) (* sinc (.z c)))
            f (* fmult (- (* cosc (.z c)) (* sinc (.y c))))
            G (m/hypot-sqrt e f)
            h-ef (+ (m/atan2 f e) hshift)
            C99 (* c99mult (/ (m/log1p (* gmult G)) gdiv))
            a99 (* C99 (m/cos h-ef))
            b99 (* C99 (m/sin h-ef))
            L99 (* l99mult (m/log1p (* lmult (.x c))))]
        (Vec4. L99 a99 b99 (.w c))))))

(defn- make-to-DIN99*
  [f n]
  (let [{[^double minr ^double maxr] :r
         [^double ming ^double maxg] :g
         [^double minb ^double maxb] :b} (din99-config n)]
    (fn ^Vec4 [c]
      (let [^Vec4 c (f c)]
        (Vec4. (m/mnorm (.x c) minr maxr 0.0 255.0)
               (m/mnorm (.y c) ming maxg 0.0 255.0)
               (m/mnorm (.z c) minb maxb 0.0 255.0)
               (.w c))))))

(defn- make-from-DIN99
  [n]
  (let [{:keys [^double cosc ^double sinc ^double fmult ^double hshift ^double c99mult
                ^double gmult ^double gdiv ^double l99mult ^double lmult]} (din99-config n)
        C99mult (/ gdiv c99mult)]
    (fn ^Vec4 [c]
      (let [^Vec4 c (pr/to-color c)
            h99 (- (m/atan2 (.z c) (.y c)) hshift)
            C99 (m/hypot-sqrt (.y c) (.z c))
            G (/ (m/expm1 (* C99mult C99)) gmult)
            e (* G (m/cos h99))
            f (/ (* G (m/sin h99)) fmult)
            a (- (* e cosc) (* f sinc))
            b (+ (* e sinc) (* f cosc))
            L (/ (m/expm1 (/ (.x c) l99mult)) lmult)]
        (from-LAB (Vec4. L a b (.w c)))))))

(defn- make-from-DIN99*
  [f n]
  (let [{[^double minr ^double maxr] :r
         [^double ming ^double maxg] :g
         [^double minb ^double maxb] :b} (din99-config n)]
    (fn ^Vec4 [c]
      (let [^Vec4 c (pr/to-color c)]
        (f (Vec4. (m/mnorm (.x c) 0.0 255.0 minr maxr)
                  (m/mnorm (.y c) 0.0 255.0 ming maxg)
                  (m/mnorm (.z c) 0.0 255.0 minb maxb)
                  (.w c)))))))

(def ^{:doc "RGB -> DIN99" :metadoc/categories meta-conv} to-DIN99 (make-to-DIN99 :din99))
(def ^{:doc "RGB -> DIN99" :metadoc/categories meta-conv} to-DIN99b (make-to-DIN99 :din99b))
(def ^{:doc "RGB -> DIN99" :metadoc/categories meta-conv} to-DIN99o (make-to-DIN99 :din99o))
(def ^{:doc "RGB -> DIN99" :metadoc/categories meta-conv} to-DIN99c (make-to-DIN99 :din99c))
(def ^{:doc "RGB -> DIN99" :metadoc/categories meta-conv} to-DIN99d (make-to-DIN99 :din99d))
(def ^{:doc "RGB -> DIN99, normalized" :metadoc/categories meta-conv} to-DIN99* (make-to-DIN99* to-DIN99 :din99))
(def ^{:doc "RGB -> DIN99b, normalized" :metadoc/categories meta-conv} to-DIN99b* (make-to-DIN99* to-DIN99b :din99b))
(def ^{:doc "RGB -> DIN99o, normalized" :metadoc/categories meta-conv} to-DIN99o* (make-to-DIN99* to-DIN99o :din99o))
(def ^{:doc "RGB -> DIN99c, normalized" :metadoc/categories meta-conv} to-DIN99c* (make-to-DIN99* to-DIN99c :din99c))
(def ^{:doc "RGB -> DIN99d, normalized" :metadoc/categories meta-conv} to-DIN99d* (make-to-DIN99* to-DIN99d :din99d))

(def ^{:doc "DIN99 -> RGB" :metadoc/categories meta-conv} from-DIN99 (make-from-DIN99 :din99))
(def ^{:doc "DIN99b -> RGB" :metadoc/categories meta-conv} from-DIN99b (make-from-DIN99 :din99b))
(def ^{:doc "DIN99o -> RGB" :metadoc/categories meta-conv} from-DIN99o (make-from-DIN99 :din99o))
(def ^{:doc "DIN99c -> RGB" :metadoc/categories meta-conv} from-DIN99c (make-from-DIN99 :din99c))
(def ^{:doc "DIN99d -> RGB" :metadoc/categories meta-conv} from-DIN99d (make-from-DIN99 :din99d))
(def ^{:doc "DIN99 -> RGB, normalized" :metadoc/categories meta-conv} from-DIN99* (make-from-DIN99* from-DIN99 :din99))
(def ^{:doc "DIN99b -> RGB, normalized" :metadoc/categories meta-conv} from-DIN99b* (make-from-DIN99* from-DIN99b :din99b))
(def ^{:doc "DIN99o -> RGB, normalized" :metadoc/categories meta-conv} from-DIN99o* (make-from-DIN99* from-DIN99o :din99o))
(def ^{:doc "DIN99c -> RGB, normalized" :metadoc/categories meta-conv} from-DIN99c* (make-from-DIN99* from-DIN99c :din99c))
(def ^{:doc "DIN99d -> RGB, normalized" :metadoc/categories meta-conv} from-DIN99d* (make-from-DIN99* from-DIN99d :din99d))

;; Other RGB

(defn ->XYZ-to-RGB
  [rgb-data-or-name]
  (let [rgb-data (get rgb/rgbs rgb-data-or-name rgb-data-or-name)
        to (rgb-data :to)
        m (rgb-data :xyz-to-rgb)]
    (fn ^Vec4 [xyz]
      (let [^Vec4 c (XYZ-to-XYZ1 xyz)
            ^Vec3 c3 (v/fmap (mat/mulv m (Vec3. (.x c) (.y c) (.z c))) to)]
        (RGB1-to-RGB (Vec4. (.x c3) (.y c3) (.z c3) (.w c)))))))

(defn ->RGB-to-XYZ
  [rgb-data-or-name]
  (let [rgb-data (get rgb/rgbs rgb-data-or-name rgb-data-or-name)
        from (rgb-data :from)
        m (rgb-data :rgb-to-xyz)]
    (fn ^Vec4 [rgb]
      (let [^Vec4 c (RGB-to-RGB1 rgb)
            ^Vec3 c3 (mat/mulv m (v/fmap (Vec3. (.x c) (.y c) (.z c)) from))]
        (XYZ1-to-XYZ (Vec4. (.x c3) (.y c3) (.z c3) (.w c)))))))

(defn ->RGB-to-RGB
  ([source-rgb-data-or-name target-rgb-data-or-name]
   (->RGB-to-RGB source-rgb-data-or-name target-rgb-data-or-name :bradford))
  ([source-rgb-data-or-name target-rgb-data-or-name adaptation-method]
   (let [source-rgb-data (get rgb/rgbs source-rgb-data-or-name source-rgb-data-or-name)
         target-rgb-data (get rgb/rgbs target-rgb-data-or-name target-rgb-data-or-name)
         ->xyz (->RGB-to-XYZ source-rgb-data)
         <-xyz (->XYZ-to-RGB target-rgb-data)
         swp (source-rgb-data :whitepoint)
         twp (target-rgb-data :whitepoint)
         adaptation-matrix (when (not= swp twp)
                             (wp/chromatic-adaptation-matrix adaptation-method swp twp))]
     (fn ^Vec4 [rgb] (if adaptation-matrix
                      (let [^Vec4 xyz (->xyz rgb)
                            ^Vec3 c (mat/mulv adaptation-matrix (Vec3. (.x xyz) (.y xyz) (.z xyz)))]
                        (<-xyz (Vec4. (.x c) (.y c) (.z c) (.w xyz))))
                      (-> rgb ->xyz <-xyz))))))

(defn ->RGB-to-linear-RGB
  [rgb-data-or-name]
  (let [from (:from (get rgb/rgbs rgb-data-or-name rgb-data-or-name))]
    (fn ^Vec4 [rgb]
      (let [^Vec4 c (RGB-to-RGB1 rgb)
            ^Vec3 c3 (v/fmap (Vec3. (.x c) (.y c) (.z c)) from)]
        (RGB1-to-RGB (Vec4. (.x c3) (.y c3) (.z c3) (.w c)))))))

(defn ->linear-RGB-to-RGB
  [rgb-data-or-name]
  (let [to (:from (get rgb/rgbs rgb-data-or-name rgb-data-or-name))]
    (fn ^Vec4 [rgb]
      (let [^Vec4 c (RGB-to-RGB1 rgb)
            ^Vec3 c3 (v/fmap (Vec3. (.x c) (.y c) (.z c)) to)]
        (RGB1-to-RGB (Vec4. (.x c3) (.y c3) (.z c3) (.w c)))))))


;; ### Grayscale

(defn to-Gray
  "RGB -> Grayscale"
  {:metadoc/categories meta-conv}
  ^Vec4 [c]
  (let [^Vec4 c (pr/to-color c)
        l (luma c)]
    (Vec4. l l l (.w c))))

(def ^{:doc "RGB -> Grayscale" :metadoc/categories meta-conv} to-Gray* to-Gray)

;; do nothing in reverse
(def ^{:doc "RGB -> Grayscale" :metadoc/categories meta-conv} from-Gray to-Gray)
(def ^{:doc "RGB -> Grayscale" :metadoc/categories meta-conv} from-Gray* to-Gray)

;; Just for a case "do nothing"
(def ^{:doc "Alias for [[to-color]]" :metadoc/categories meta-conv} to-RGB pr/to-color)
(def ^{:doc "Alias for [[to-color]]" :metadoc/categories meta-conv} from-RGB pr/to-color)
(def ^{:doc "Alias for [[to-color]]" :metadoc/categories meta-conv} to-RGB* pr/to-color)
(def ^{:doc "Alias for [[to-color]]" :metadoc/categories meta-conv} from-RGB* pr/to-color)

(def ^{:doc "sRGB -> linearRGB" :metadoc/categories meta-conv} to-linearRGB from-sRGB)
(def ^{:doc "linearRGB -> sRGB" :metadoc/categories meta-conv} from-linearRGB to-sRGB)
(def ^{:doc "sRGB -> linearRGB" :metadoc/categories meta-conv} to-linearRGB* from-sRGB)
(def ^{:doc "linearRGB -> sRGB" :metadoc/categories meta-conv} from-linearRGB* to-sRGB)

;; List of all color spaces with functions
(def ^{:doc "Map of all color spaces functions.

* key - name as keyword
* value - vector with functions containing to-XXX and from-XXX converters."
     :metadoc/categories meta-conv}
  colorspaces {:CMY         [to-CMY from-CMY]
               :OHTA        [to-OHTA from-OHTA]
               :XYZ         [to-XYZ from-XYZ]
               :XYZ1        [to-XYZ1 from-XYZ1]
               :DIN99       [to-DIN99 from-DIN99]
               :DIN99b      [to-DIN99b from-DIN99b]
               :DIN99c      [to-DIN99c from-DIN99c]
               :DIN99d      [to-DIN99d from-DIN99d]
               :DIN99o      [to-DIN99o from-DIN99o]
               :UCS         [to-UCS from-UCS]
               :UVW         [to-UVW from-UVW]
               :XYB         [to-XYB from-XYB]
               :RYB         [to-RYB from-RYB]
               :Yxy         [to-Yxy from-Yxy]
               :LMS         [to-LMS from-LMS]
               :IPT         [to-IPT from-IPT]
               :IgPgTg      [to-IgPgTg from-IgPgTg]
               :LUV         [to-LUV from-LUV]
               :LAB         [to-LAB from-LAB]
               :Oklab       [to-Oklab from-Oklab]
               :Oklch       [to-Oklch from-Oklch]
               :Okhsv       [to-Okhsv from-Okhsv]
               :Okhwb       [to-Okhwb from-Okhwb]
               :Okhsl       [to-Okhsl from-Okhsl]
               :JAB         [to-JAB from-JAB]
               :HunterLAB   [to-HunterLAB from-HunterLAB]
               :LCH         [to-LCH from-LCH]
               :LCHuv       [to-LCHuv from-LCHuv]
               :JCH         [to-JCH from-JCH]
               :HCL         [to-HCL from-HCL]
               :HSB         [to-HSB from-HSB]
               :HSI         [to-HSI from-HSI]
               :HSL         [to-HSL from-HSL]
               :HSV         [to-HSV from-HSV]
               :PalettonHSV [to-PalettonHSV from-PalettonHSV]
               :HWB         [to-HWB from-HWB]
               :GLHS        [to-GLHS from-GLHS]
               :YPbPr       [to-YPbPr from-YPbPr]
               :YDbDr       [to-YDbDr from-YDbDr]
               :YCbCr       [to-YCbCr from-YCbCr]
               :YCgCo       [to-YCgCo from-YCgCo]
               :YUV         [to-YUV from-YUV]
               :YIQ         [to-YIQ from-YIQ]
               :Gray        [to-Gray from-Gray]
               :sRGB        [to-sRGB from-sRGB]
               :linearRGB   [from-sRGB to-sRGB]
               :Cubehelix   [to-Cubehelix from-Cubehelix]
               :OSA         [to-OSA from-OSA]
               :RGB         [to-color to-color]
               :pass        [to-color to-color]})

(def ^{:doc "Map of all color spaces functions (normalized).

* key - name as keyword
* value - vector with functions containing to-XXX* and from-XXX* converters."
     :metadoc/categories meta-conv}
  colorspaces* {:CMY         [to-CMY* from-CMY*]
                :OHTA        [to-OHTA* from-OHTA*]
                :XYZ         [to-XYZ* from-XYZ*]
                :XYZ1        [to-XYZ1* from-XYZ1*]
                :DIN99       [to-DIN99* from-DIN99*]
                :DIN99b      [to-DIN99b* from-DIN99b*]
                :DIN99c      [to-DIN99c* from-DIN99c*]
                :DIN99d      [to-DIN99d* from-DIN99d*]
                :DIN99o      [to-DIN99o* from-DIN99o*]
                :UCS         [to-UCS* from-UCS*]
                :UVW         [to-UVW* from-UVW*]
                :XYB         [to-XYB* from-XYB*]
                :RYB         [to-RYB* from-RYB*]
                :Yxy         [to-Yxy* from-Yxy*]
                :LMS         [to-LMS* from-LMS*]
                :IPT         [to-IPT* from-IPT*]
                :IgPgTg      [to-IgPgTg* from-IgPgTg*]
                :LUV         [to-LUV* from-LUV*]
                :LAB         [to-LAB* from-LAB*]
                :Oklab       [to-Oklab* from-Oklab*]
                :Oklch       [to-Oklch* from-Oklch*]
                :Okhsv       [to-Okhsv* from-Okhsv*]
                :Okhwb       [to-Okhwb* from-Okhwb*]
                :Okhsl       [to-Okhsl* from-Okhsl*]
                :JAB         [to-JAB* from-JAB*]
                :HunterLAB   [to-HunterLAB* from-HunterLAB*]
                :LCH         [to-LCH* from-LCH*]
                :LCHuv       [to-LCHuv* from-LCHuv*]
                :JCH         [to-JCH* from-JCH*]
                :HCL         [to-HCL* from-HCL*]
                :HSB         [to-HSB* from-HSB*]
                :HSI         [to-HSI* from-HSI*]
                :HSL         [to-HSL* from-HSL*]
                :HSV         [to-HSV* from-HSV*]
                :PalettonHSV [to-PalettonHSV* from-PalettonHSV*]
                :HWB         [to-HWB* from-HWB*]
                :GLHS        [to-GLHS* from-GLHS*]
                :YPbPr       [to-YPbPr* from-YPbPr*]
                :YDbDr       [to-YDbDr* from-YDbDr*]
                :YCbCr       [to-YCbCr* from-YCbCr*]
                :YCgCo       [to-YCgCo* from-YCgCo*]
                :YUV         [to-YUV* from-YUV*]
                :YIQ         [to-YIQ* from-YIQ*]
                :Gray        [to-Gray from-Gray]
                :sRGB        [to-sRGB* from-sRGB*]
                :linearRGB   [from-sRGB* to-sRGB*]
                :Cubehelix   [to-Cubehelix* from-Cubehelix*]
                :OSA         [to-OSA* from-OSA*]
                :RGB         [to-color to-color]
                :pass        [to-color to-color]})

;; List of color spaces names
(def ^{:doc "List of all color space names." :metadoc/categories meta-conv} colorspaces-list (sort (keys colorspaces)))

;;

(defn spectrum
  "Create Spectrum object from data"
  ([lambda value] (let [start (reduce min lambda)
                        end (reduce max lambda)]
                    {:range [start end] :lambda lambda :value value}))
  ([range lambda value] {:range range :lambda lambda :value value}))

(defn ->spectrum-to-XYZ1
  "Build a converter of spectrum data to XYZ for given observant and illuminant.

  Y is normalized to a 0-1 range.

  Additional parameters:
  * `:interpolation` - method of interpolation, default: `:linear` (also: `:cubic`, `:step`, see `fastmath.interpolation`)
  * `:extrapolation` - what to do outside given range
      - `:trim` - trim ranges to one common range (default)
      - `:constant` - constant value from the boundaries
      - `nil` - extrapolation is done by interpolation function
  * `step` - distance between consecutive frequencies (default: `1.0`)

  Returned function accepts spectrum data which is a map containing:

  * `:lambda` - sequence of frequencies
  * `:values` - sequence of values
  * `:range` - range of frequencies"
  (^Vec4 [] (->spectrum-to-XYZ1 :CIE-2 :D65))
  (^Vec4 [observer illuminant] (->spectrum-to-XYZ1 observer illuminant nil))
  (^Vec4 [observer illuminant {:keys [interpolation ^double step extrapolation]
                               :or {interpolation :linear step 1.0 extrapolation :trim}}]
   (let [trim? (= :trim extrapolation)
         illuminant (get wp/illuminants-spectrum-data illuminant illuminant)
         observer (get wp/color-matching-functions-data observer observer)
         [^long si ^long ei] (:range illuminant)
         [^long so ^long eo] (:range observer)
         [^long start ^long end] (if trim?
                                   [(m/max si so) (m/min ei eo)]
                                   [(m/min si so) (m/max ei eo)])
         r (range start (m/+ step end) step)
         lo (:lambda observer)
         i (-> (i/interpolation interpolation (:lambda illuminant) (:value illuminant))
               (i/extrapolation extrapolation si ei))
         x (-> (i/interpolation interpolation lo (:x observer)) (i/extrapolation extrapolation so eo))
         y (-> (i/interpolation interpolation lo (:y observer)) (i/extrapolation extrapolation so eo))
         z (-> (i/interpolation interpolation lo (:z observer)) (i/extrapolation extrapolation so eo))
         n (v/sum (v/emult (map i r) (map y r)))]
     (fn [{:keys [lambda value] :as spectrum}]
       (let [[^long s ^long e] (:range spectrum)
             [^long start1 ^long end1] (if trim?
                                         [(m/max s start) (m/min e end)]
                                         [(m/min s start) (m/max e end)])
             r1 (range start1 (m/+ step end1) step)
             s (-> (i/interpolation interpolation lambda value)
                   (i/extrapolation extrapolation s e))
             sx (map x r1)
             sy (map y r1)
             sz (map z r1)
             sv (map s r1)
             so (map i r1)
             svo (v/emult sv so)]         
         (v/div (Vec3. (v/sum (v/emult svo sx))
                       (v/sum (v/emult svo sy))
                       (v/sum (v/emult svo sz))) n))))))

(defn ->spectrum-to-XYZ
  "Build a converter of spectrum data to XYZ for given observant and illuminant.

  Y is normalized to a 0-100 range.

  Additional parameters:
  * `:interpolation` - method of interpolation, default: `:linear` (also: `:cubic`, `:step`, see `fastmath.interpolation`)
  * `:extrapolation` - what to do outside given range
      - `:trim` - trim ranges to one common range (default)
      - `:constant` - constant value from the boundaries
      - `nil` - extrapolation is done by interpolation function
  * `step` - distance between consecutive frequencies (default: `1.0`)

  Returned function accepts spectrum data which is a map containing:

  * `:lambda` - sequence of frequencies
  * `:values` - sequence of values
  * `:range` - range of frequencies"
  ([] (->spectrum-to-XYZ :CIE-2 :D65))
  ([observer illuminant] (->spectrum-to-XYZ observer illuminant nil))
  ([observer illuminant options] (let [->xyz1 (->spectrum-to-XYZ1 observer illuminant options)]
                                   (fn [spectrum]
                                     (-> spectrum ->xyz1 XYZ1-to-XYZ)))))

;;

(defn make-LCH
  "Create LCH conversion functions pair from any luma based color space. "
  [cs]
  (let [[to from] (colorspaces cs)]
    [(partial to-luma-color-hue to)
     (partial from-luma-color-hue from)]))

(defn complementary
  "Create complementary color. Possible colorspaces are:

  * `:PalettonHSV` (default)
  * `:HSB`, `:HSV`, `:HSL` or `:HWB`
  * `:GLHS`"
  (^Vec4 [c] (complementary c :PalettonHSV))
  (^Vec4 [c colorspace]
   (let [[to from] (colorspaces colorspace)
         ^Vec4 c (to c)]
     (from (if (= colorspace :GLHS)
             (Vec4. (+ 180.0 (.x c)) (.y c) (.z c) (.w c))
             (Vec4. (.x c) (+ 180.0 (.y c)) (.z c) (.w c)))))))

(defn set-channel
  "Set chosen channel with given value. Works with any color space."
  {:metadoc/categories meta-ops}
  (^Vec4 [col colorspace ^long channel ^double val]
   (let [[to from] (colorspaces colorspace)
         ^Vec4 c (to col)]
     (from (case channel
             0 (Vec4. val (.y c) (.z c) (.w c))
             1 (Vec4. (.x c) val (.z c) (.w c))
             2 (Vec4. (.x c) (.y c) val (.w c))
             3 (Vec4. (.x c) (.y c) (.z c) val)
             c))))
  (^Vec4 [col ^long channel ^double val]
   (let [^Vec4 c (pr/to-color col)]
     (case channel
       0 (Vec4. val (.y c) (.z c) (.w c))
       1 (Vec4. (.x c) val (.z c) (.w c))
       2 (Vec4. (.x c) (.y c) val (.w c))
       3 (Vec4. (.x c) (.y c) (.z c) val)
       c))))

(defn get-channel
  "Get chosen channel. Works with any color space."
  {:metadoc/categories meta-ops}
  (^double [col colorspace ^long channel]
   (let [to (first (colorspaces colorspace))]
     (get-channel (to col) channel)))
  (^double [col ^long channel]
   (case channel
     0 (ch0 col)
     1 (ch1 col)
     2 (ch2 col)
     3 (alpha col)
     ##NaN)))

(defn color-converter
  "Create function which converts provided color from `cs` color space using `ch-scale` as maximum value. (to simulate Processing `colorMode` fn).

  Arity:

  * 1 - returns from-XXX* function
  * 2 - sets the same maximum value for each channel
  * 3 - sets individual maximum value without alpha, which is set to 0-255 range
  * 4 - all channels have it's own individual maximum value."
  {:metadoc/categories meta-conv}
  ([cs ch1-scale ch2-scale ch3-scale ch4-scale]
   (let [colorspace-fn (second (colorspaces* cs))]
     (fn [v] 
       (let [^Vec4 v (pr/to-color v)
             ch1 (* 255.0 (/ (.x v) ^double ch1-scale))
             ch2 (* 255.0 (/ (.y v) ^double ch2-scale))
             ch3 (* 255.0 (/ (.z v) ^double ch3-scale))
             ch4 (* 255.0 (/ (.w v) ^double ch4-scale))]
         (colorspace-fn (v/fmap (Vec4. ch1 ch2 ch3 ch4) clamp255))))))
  ([cs ch1-scale ch2-scale ch3-scale] (color-converter cs ch1-scale ch2-scale ch3-scale 255.0))
  ([cs ch-scale] (color-converter cs ch-scale ch-scale ch-scale ch-scale))
  ([cs] (second (colorspaces* cs))))

;; black body

(defn- kelvin-red
  ^double [^double k ^double lnk]
  (if (< k 0.65)
    255.0
    (min 255.0 (* 255.0 (rgb/linear-to-srgb (+ 0.32068362618584273
                                               (* 0.19668730877673762 (m/pow (+ -0.21298613432655075 k) -1.5139012907556737))
                                               (* -0.013883432789258415 lnk)))))))

(defn- kelvin-green1
  ^double [^double k ^double lnk]
  (let [eek (+ k -0.44267061967913873)]
    (max 0.0 (* 255.0 (rgb/linear-to-srgb (+ 1.226916242502167
                                             (* -1.3109482654223614 eek eek eek (m/exp (* eek -5.089297600846147)))
                                             (* 0.6453936305542096 lnk)))))))

(defn- kelvin-green2
  ^double [^double k ^double lnk]
  (* 255.0 (rgb/linear-to-srgb (+ 0.4860175851734596
                                  (* 0.1802139719519286 (m/pow (+ -0.14573069517701578 k) -1.397716496795082))
                                  (* -0.00803698899233844 lnk)))))


(defn- kelvin-green
  ^double [^double k ^double lnk]
  (cond
    (< k 0.08) 0.0
    (< k 0.655)(kelvin-green1 k lnk)
    :else (kelvin-green2 k lnk)))

(defn- kelvin-blue
  ^double [^double k ^double lnk]
  (cond
    (< k 0.19) 0.0
    (> k 0.66) 255.0
    :else (let [eek (+ k -1.1367244820333684)]
            (m/constrain (* 255.0 (rgb/linear-to-srgb (+ 1.677499032830161
                                                         (* -0.02313594016938082 eek eek eek
                                                            (m/exp (* eek -4.221279555918655)))
                                                         (* 1.6550275798913296 lnk)))) 0.0 255.0))))

(def ^:private temperature-name-to-K
  {:candle 1800.0
   :sunrise 2500.0
   :sunset 2500.0
   :lightbulb 2900.0
   :morning 3500.0
   :moonlight 4000.0
   :midday 5500.0
   :cloudy-sky 6500.0
   :blue-sky 10000.0
   :warm 2900.0
   :white 4250.0
   :sunlight 4800.0
   :cool 7250.0})

(def temperature-names (sort (keys temperature-name-to-K)))

(defn temperature
  "Color representing given black body temperature `t` in Kelvins (or name as keyword).

  Reference: CIE 1964 10 degree CMFs
  
  Using improved interpolation functions.

  Possible temperature names: `:candle`, `:sunrise`, `:sunset`, `:lightbulb`, `:morning`, `:moonlight`, `:midday`, `:cloudy-sky`, `:blue-sky`, `:warm`, `:cool`, `:white`, `:sunlight`"
  {:metadoc/categories #{:pal}}
  ^Vec4 [t]
  (let [k (* 0.0001 ^double (get temperature-name-to-K t t))
        lnk (m/ln k)]
    (Vec4. (kelvin-red k lnk)
           (kelvin-green k lnk)
           (kelvin-blue k lnk)
           255.0)))

(defn wavelength-to-XYZ
  (^Vec4 [^double lambda] (wavelength-to-XYZ lambda :CIE-2))
  (^Vec4 [^double lambda observer]
   (if (= :CIE-2 observer)
     (XYZ1-to-XYZ (Vec3. (wp/cmf-cie2-x lambda)
                         (wp/cmf-cie2-y lambda)
                         (wp/cmf-cie2-z lambda)))
     (XYZ1-to-XYZ (Vec3. (wp/cmf-cie10-x lambda)
                         (wp/cmf-cie10-y lambda)
                         (wp/cmf-cie10-z lambda))))))

(defn wavelength
  "Returns color from given wavelength in nm"
  (^Vec4 [^double lambda] (wavelength lambda :CIE-2))
  (^Vec4 [^double lambda observer]
   (-> lambda (wavelength-to-XYZ observer) from-XYZ clamp)))

;; http://iquilezles.org/www/articles/palettes/palettes.htm

(defn- cosine-coefficients
  "Computes coefficients defining a cosine gradient between
  the two given colors. The colors can be in any color space,
  but the resulting gradient will always be computed in RGB.

  amp = (R1 - R2) / 2
  dc = R1 - amp
  freq = -0.5

  Code borrowed from thi.ng/color"
  ([c1 c2]
   (let [c1 (v/vec3 (pr/red c1) (pr/green c1) (pr/blue c1))
         c2 (v/vec3 (pr/red c2) (pr/green c2) (pr/blue c2))
         amp (v/mult (v/sub c1 c2) 0.5)
         offset (v/sub c1 amp)]
     [(v/div offset 255.0)
      (v/div amp 255.0)
      (v/vec3 -0.500 -0.500 -0.500)
      (v/vec3)])))

(defn iq-gradient
  "Create gradient generator function with given parametrization or two colors.

  See http://iquilezles.org/www/articles/palettes/palettes.htm and https://github.com/thi-ng/color/blob/master/src/gradients.org#gradient-coefficient-calculation

  Parameters should be `Vec3` type."
  {:metadoc/categories #{:gr}}
  ([c1 c2]
   (apply iq-gradient (cosine-coefficients c1 c2)))
  ([a b c d]
   (fn ^Vec4 [^double t]
     (let [^Vec3 cc (apply v/vec3 (-> (->> (v/mult c t)
                                           (v/add d))
                                      (v/mult m/TWO_PI)
                                      (v/fmap #(m/cos %))
                                      (v/emult b)
                                      (v/add a)))]
       (-> (Vec4. (.x cc) (.y cc) (.z cc) 1.0)
           (v/mult 255.0)
           (v/fmap clamp255))))))

;; --------------

(defonce ^:private paletton-base-data
  (let [s (fn ^double [^double e ^double t ^double n] (if (== n -1.0) e
                                                          (+ e (/ (- t e) (inc n)))))
        i (fn ^double [^double e ^double t ^double n] (if (== n -1.0) t
                                                          (+ t (/ (- e t) (inc n)))))
        d120 {:a [1.0 1.0]
              :b [1.0 1.0]
              :f (fn ^double [^double e]
                   (if (zero? e) -1.0
                       (* 0.5 (m/tan (* m/HALF_PI (/ (- 120.0 e) 120.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 0.0
                        (- 120.0 (* 2.0 (/ (* (m/atan (/ e 0.5)) 120.0) m/PI)))))
              :g s
              :rgb (fn [e n r a] (Vec4. e n r a))}
        d180 {:a [1.0 1.0]
              :b [1.0 0.8]
              :f (fn ^double [^double e]
                   (if (== e 180.0) -1.0
                       (* 0.5 (m/tan (* m/HALF_PI (/ (- e 120.0) 60.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 180.0
                        (+ 120.0 (* 2.0 (/ (* (m/atan (/ e 0.5)) 60.0) m/PI)))))
              :g i
              :rgb (fn [e n r a] (Vec4. n e r a))}
        d210 {:a [1.0 0.8]
              :b [1.0 0.6]
              :f (fn ^double [^double e]
                   (if (== e 180.0) -1.0
                       (* 0.75 (m/tan (* m/HALF_PI (/ (- 210.0 e) 30.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 180.0
                        (- 210.0 (* 2.0 (/ (* (m/atan (/ e 0.75)) 30.0) m/PI)))))
              :g s
              :rgb (fn [e n r a] (Vec4. r e n a))}
        d255 {:a [1.0 0.6]
              :b [0.85 0.7]
              :f (fn ^double [^double e]
                   (if (== e 255.0) -1.0
                       (* 1.33 (m/tan (* m/HALF_PI (/ (- e 210.0) 45.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 255.0
                        (+ 210.0 (* 2.0 (/ (* (m/atan (/ e 1.33)) 45.0) m/PI)))))
              :g i
              :rgb (fn [e n r a] (Vec4. r n e a))}
        d315 {:a [0.85 0.7]
              :b [1.0 0.65]
              :f (fn ^double [^double e]
                   (if (== e 255.0) -1.0
                       (* 1.33 (m/tan (* m/HALF_PI (/ (- 315.0 e) 60.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 255.0
                        (- 315.0 (* 2.0 (/ (* (m/atan (/ e 1.33)) 60.0) m/PI)))))
              :g s
              :rgb (fn [e n r a] (Vec4. n r e a))}
        d360 {:a [1.0 0.65]
              :b [1.0 1.0]
              :f (fn ^double [^double e]
                   (if (zero? e) -1.0
                       (* 1.33 (m/tan (* m/HALF_PI (/ (- e 315.0) 45.0))))))
              :fi (fn ^double [^double e]
                    (if (== e -1.0) 0.0
                        (+ 315.0 (* 2.0 (/ (* (m/atan (/ e 1.33)) 45.0) m/PI)))))
              :g i
              :rgb (fn [e n r a] (Vec4. e r n a))}]
    (fn [^double h]
      (condp clojure.core/> h
        120.0 d120
        180.0 d180
        210.0 d210
        255.0 d255
        315.0 d315
        360.0 d360))))

(defn- adjust-s-v-rev
  ^double [^double e ^double t] (if (<= t 1.0)
                                  (* e t)
                                  (+ e (* (- 1.0 e) (dec t)))))

(defn- paletton-hsv-to-rgb
  "Paletton version of HSV to RGB converter"
  (^Vec4 [^double hue ^double ks ^double kv ^double alpha]
   (let [ks (m/constrain ks 0.0 2.0)
         kv (m/constrain kv 0.0 2.0)
         h (wrap-hue hue)
         {:keys [a b f g rgb]} (paletton-base-data h)
         av (second a)
         bv (second b)
         as (first a)
         bs (first b)
         ^double n (f h)
         v (adjust-s-v-rev (g av bv n) kv)
         s (adjust-s-v-rev (g as bs n) ks)
         r (* 255.0 v)
         b (* r (- 1.0 s))
         g (if (== n -1.0) b
               (/ (+ r (* n b)) (inc n)))]
     (rgb r g b alpha)))
  (^Vec4 [^double hue ^double ks ^double kv]
   (paletton-hsv-to-rgb hue ks kv 255.0)))

(defn hue-paletton
  "Convert color to paletton HUE (which is different than hexagon or polar conversion)."
  {:metadoc/categories meta-ops}
  (^double [^double r ^double g ^double b]
   (if (== r g b)
     0.0
     (let [f (max r g b)
           p (min r g b)
           [^double l i] (if (== f r)
                           (if (== p b)
                             [g (:fi (paletton-base-data 119.0))]
                             [b (:fi (paletton-base-data 359.0))])
                           (if (== f g)
                             (if (== p r)
                               [b (:fi (paletton-base-data 209.0))]
                               [r (:fi (paletton-base-data 179.0))])
                             (if (== p r)
                               [g (:fi (paletton-base-data 254.0))]
                               [r (:fi (paletton-base-data 314.0))])))
           s (i (if (== l p) -1.0
                    (/ (- f l) (- l p))))]
       s)))
  ([c] (let [cc (to-color c)]
         (hue-paletton (.x cc) (.y cc) (.z cc)))))

(defn- get-s-v
  [^double h]
  (let [{:keys [a b f g]} (paletton-base-data h)
        av (second a)
        bv (second b)
        as (first a)
        bs (first b)
        r (f h)
        s (g av bv r)
        i (g as bs r)]
    [i s]))

(defn- adjust-s-v
  ^double [^double e ^double t]
  (if (zero? e)
    0.0
    (if (<= t e)
      (/ t e)
      (inc (/ (- t e) (- 1.0 e))))))

(defn- paletton-rgb-to-hsv
  (^Vec4 [^double r ^double g ^double b ^double alpha]
   (if (== r g b)
     (Vec4. 0.0 0.0 (/ (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)) 255.0) alpha)
     (let [f (max r g b)
           p (min r g b)
           h (hue-paletton r g b)
           [s v] (get-s-v h)
           n (adjust-s-v s (/ (- f p) f))
           r (adjust-s-v v (/ f 255.0))]
       (Vec4. h n r alpha))))
  (^Vec4 [^double r ^double g ^double b]
   (paletton-rgb-to-hsv r g b 255.0)))

;; List of paletton presets
(defonce ^:private paletton-presets
  {:pale-light          [[0.24649 1.78676] [0.09956 1.95603] [0.17209 1.88583] [0.32122 1.65929] [0.39549 1.50186]]
   :pastels-bright      [[0.65667 1.86024] [0.04738 1.99142] [0.39536 1.89478] [0.90297 1.85419] [1.86422 1.8314]]
   :shiny               [[1.00926 2]       [0.3587 2]        [0.5609 2]        [2 0.8502] [2 0.65438]]
   :pastels-lightest    [[0.34088 1.09786] [0.13417 1.62645] [0.23137 1.38072] [0.45993 0.92696] [0.58431 0.81098]]
   :pastels-very-light  [[0.58181 1.32382] [0.27125 1.81913] [0.44103 1.59111] [0.70192 1.02722] [0.84207 0.91425]]
   :full                [[1 1]             [0.61056 1.24992] [0.77653 1.05996] [1.06489 0.77234] [1.25783 0.60685]]
   :pastels-light       [[0.37045 0.90707] [0.15557 1.28367] [0.25644 1.00735] [0.49686 0.809] [0.64701 0.69855]]
   :pastels-med         [[0.66333 0.8267]  [0.36107 1.30435] [0.52846 0.95991] [0.78722 0.70882] [0.91265 0.5616]]
   :darker              [[0.93741 0.68672] [0.68147 0.88956] [0.86714 0.82989] [1.12072 0.5673] [1.44641 0.42034]]
   :pastels-mid-pale    [[0.38302 0.68001] [0.15521 0.98457] [0.26994 0.81586] [0.46705 0.54194] [0.64065 0.44875]]
   :pastels             [[0.66667 0.66667] [0.33333 1]       [0.5 0.83333]     [0.83333 0.5] [1 0.33333]]
   :dark-neon           [[0.94645 0.59068] [0.99347 0.91968] [0.93954 0.7292]  [1.01481 0.41313] [1.04535 0.24368]]
   :pastels-dark        [[0.36687 0.39819] [0.25044 0.65561] [0.319 0.54623]   [0.55984 0.37953] [0.70913 0.3436]]
   :pastels-very-dark   [[0.60117 0.41845] [0.36899 0.59144] [0.42329 0.44436] [0.72826 0.35958] [0.88393 0.27004]]
   :dark                [[1.31883 0.40212] [0.9768 0.25402]  [1.27265 0.30941] [1.21289 0.60821] [1.29837 0.82751]]
   :pastels-mid-dark    [[0.26952 0.22044] [0.23405 0.52735] [0.23104 0.37616] [0.42324 0.20502] [0.54424 0.18483]]
   :pastels-darkest     [[0.53019 0.23973] [0.48102 0.50306] [0.50001 0.36755] [0.6643 0.32778] [0.77714 0.3761]]
   :darkest             [[1.46455 0.21042] [0.99797 0.16373] [0.96326 0.274]   [1.56924 0.45022] [1.23016 0.66]]
   :almost-black        [[0.12194 0.15399] [0.34224 0.50742] [0.24211 0.34429] [0.31846 0.24986] [0.52251 0.33869]]
   :almost-gray-dark    [[0.10266 0.24053] [0.13577 0.39387] [0.11716 0.30603] [0.14993 0.22462] [0.29809 0.19255]]
   :almost-gray-darker  [[0.07336 0.36815] [0.18061 0.50026] [0.09777 0.314]   [0.12238 0.25831] [0.14388 0.1883]]
   :almost-gray-mid     [[0.07291 0.59958] [0.19602 0.74092] [0.10876 0.5366]  [0.15632 0.48229] [0.20323 0.42268]]
   :almost-gray-lighter [[0.06074 0.82834] [0.14546 0.97794] [0.10798 0.76459] [0.15939 0.68697] [0.22171 0.62926]]
   :almost-gray-light   [[0.03501 1.59439] [0.23204 1.10483] [0.14935 1.33784] [0.07371 1.04897] [0.09635 0.91368]]})

;; List of preset names
(def ^{:doc "Paletton presets names"
       :metadoc/categories #{:pal}}
  paletton-presets-list (keys paletton-presets))

(defn- paletton-monochromatic-palette
  "Create monochromatic palette for given `hue` (0-360) and `preset` values."
  [hue preset]
  (mapv (fn [[ks kv]] (paletton-hsv-to-rgb hue ks kv)) preset))

(defmulti paletton
  "Create [paletton](http://paletton.com/) palette.

  Input:

  * type - one of: `:monochromatic` (one hue), `:triad` (three hues), `:tetrad` (four hues)
  * hue - paletton version of hue (use [[hue-paletton]] to get hue value).
  * configuration as a map

  Configuration consist:

  * `:preset` - one of [[paletton-presets-list]], default `:full`.
  * `:compl` - generate complementary color?, default `false`. Works with `:monochromatic` and `:triad`
  * `:angle` - hue angle for additional colors for `:triad` and `:tetrad`.
  * `:adj` - for `:triad` only, generate adjacent (default `true`) values or not."
  {:metadoc/categories #{:pal}}
  (fn [m _ & _] m))

(defmethod paletton :monochromatic [_ hue & conf]
  (let [{compl :compl 
         preset :preset
         :or {compl false
              preset :full}} (first conf)
        ppreset (if (keyword? preset) (paletton-presets preset) preset)
        p (paletton-monochromatic-palette hue ppreset)]
    (if compl (vec (concat p (paletton-monochromatic-palette (+ ^double hue 180.0) ppreset))) p)))

(defmethod paletton :triad [_ hue & conf]
  (let [{compl :compl
         preset :preset
         ^double angle :angle
         adj :adj
         :or {compl false
              preset :full
              angle 30.0
              adj true}} (first conf)
        chue (+ 180.0 ^double hue)
        hue1 (if adj (+ ^double hue angle) (+ chue angle))
        hue2 (if adj (- ^double hue angle) (- chue angle))
        ppreset (if (keyword? preset) (paletton-presets preset) preset)
        p1 (paletton-monochromatic-palette hue ppreset)
        p2 (paletton-monochromatic-palette hue1 ppreset)
        p3 (paletton-monochromatic-palette hue2 ppreset)
        p (vec (concat p1 p2 p3))]
    (if compl (vec (concat p (paletton-monochromatic-palette chue ppreset))) p)))

(defmethod paletton :tetrad [_ hue & conf]
  (let [{preset :preset
         ^double angle :angle
         :or {preset :full
              angle 30.0}} (first conf)
        p1 (paletton :monochromatic hue {:preset preset :compl true})
        p2 (paletton :monochromatic (+ angle ^double hue) {:preset preset :compl true})]
    (vec (concat p1 p2))))

;; ## Additional functions
;; https://www.imatest.com/docs/colorcheck_ref/

(defn delta-E*
  "ΔE*_ab difference, CIE 1976"
  (^double [c1 c2] (delta-E* c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)]
     (m/hypot-sqrt (- (.x c2) (.x c1))
                   (- (.y c2) (.y c1))
                   (- (.z c2) (.z c1))))))

(defn- delta-ab
  ^double [^Vec4 c1 ^Vec4 c2]
  (- (m/hypot-sqrt (.y c1) (.z c1))
     (m/hypot-sqrt (.y c2) (.z c2))))

(defn delta-C*
  "ΔC*_ab difference, chroma difference in LAB color space, CIE 1976"
  {:metadoc/categories #{:dist}}
  (^double [c1 c2] (delta-C* c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))]
     (delta-ab (to c1) (to c2)))))

(def ^{:deprecated "Use delta-C*"} delta-c delta-C*)

(defn delta-H*
  "ΔH* difference, hue difference in LAB, CIE 1976"
  {:metadoc/categories #{:dist}}
  (^double [c1 c2] (delta-H* c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)]
     (m/safe-sqrt (- (+ (m/sq (- (.y c2) (.y c1)))
                        (m/sq (- (.z c2) (.z c1))))
                     (m/sq (delta-ab c1 c2)))))))

(def ^{:deprecated "Use delta-H*"} delta-h delta-H*)

(defn delta-E-HyAB
  "ΔE_HyAB difference"
  (^double [c1 c2] (delta-E-HyAB c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)]
     (+ (m/hypot-sqrt (- (.y c2) (.y c1))
                      (- (.z c2) (.z c1)))
        (m/abs (- (.x c2) (.x c1)))))))

(defn delta-E*-94
  "ΔE* difference, CIE 1994"
  {:metadoc/categories #{:dist}}
  (^double [c1 c2] (delta-E*-94 c1 c2 nil))
  (^double [c1 c2 {:keys [textiles? colorspace]
                   :or {textiles? false colorspace :LAB}}]
   (let [k1 (if textiles? 0.048 0.045)
         k2 (if textiles? 0.014 0.015)
         to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)
         C1* (m/hypot-sqrt (.y c1) (.z c1))
         C2* (m/hypot-sqrt (.y c2) (.z c2))
         Sc (inc (* k1 C1*))
         Sh (inc (* k2 C1*))
         SL (if textiles? 2.0 1.0)
         dab (- C1* C2*)]
     (m/sqrt (+ (m/sq (/ (- (.x c2) (.x c1)) SL))
                (m/sq (/ dab Sc))
                (m/sq (/ (m/safe-sqrt (- (+ (m/sq (- (.y c2) (.y c1)))
                                            (m/sq (- (.z c2) (.z c1))))
                                         (m/sq dab))) Sh)))))))

(def ^{:metadoc/categories #{:dist} :deprecated "Use delta-E*"
     :doc "Delta E CIE distance (euclidean in LAB colorspace."}
  delta-e-cie delta-E*)

(defn delta-E*-euclidean
  ^{:metadoc/categories #{:dist}
    :doc "Euclidean distance in given colorspace (default Oklab)."}
  (^double [c1 c2] (delta-E*-euclidean c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :Oklab}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)]
     (m/hypot-sqrt (- (.x c2) (.x c1))
                   (- (.y c2) (.y c1))
                   (- (.z c2) (.z c1))))))

(defn delta-C-RGB
  "ΔC in RGB color space"
  ^double [c1 c2]
  (let [^Vec4 c1 (pr/to-color c1)
        ^Vec4 c2 (pr/to-color c2)
        dR (- (.x c2) (.x c1))
        dG (- (.y c2) (.y c1))
        dB (- (.z c2) (.z c1))
        r (* 0.5 (+ (.x c1) (.x c2)))]
    (m/sqrt (+ (* (+ 2.0 (/ r 256.0)) dR dR)
               (* 4.0 dG dG)
               (* (+ 2.0 (/ (- 255.0 r) 256.0)) dB dB)))))

(defn- diff-H
  ^double [^double h1 ^double h2]
  (if (pos? (* h1 h2))
    (m/abs (- h1 h2))
    (min (+ (m/abs h1) (m/abs h2))
         (+ (- 180.0 (m/abs h1)) (- 180.0 (m/abs h2))))))

;; http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.125.3833&rep=rep1&type=pdf
(defn delta-D-HCL
  "Color difference in HCL (Sarifuddin and Missaou) color space"
  (^double [c1 c2] (delta-D-HCL c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :HCL}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)
         dH (diff-H (.x c2) (.x c1))
         dL (- (.z c2) (.z c1))]
     (m/sqrt (+ (m/sq (* 1.4456 dL))
                (* (+ dH 0.16)
                   (+ (m/sq (.y c1))
                      (m/sq (.y c2))
                      (* -2.0 (.y c1) (.y c2) (m/cos (m/radians dH))))))))))

(defn delta-E*-CMC
  "ΔE* CMC difference

  Parameters `l` and `c` defaults to 1.0. Other common settings is `l=2.0` and `c=1.0`."
  {:metadoc/categories #{:dist}}
  (^double [c1 c2] (delta-E*-CMC c1 c2 nil))
  (^double [c1 c2 {:keys [^double l ^double c colorspace]
                   :or {l 1.0 c 1.0 colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)
         L1 (.x c1)
         L2 (.x c2)
         a1 (.y c1)
         a2 (.y c2)
         b1 (.z c1)
         b2 (.z c2)
         H (m/degrees (m/atan2 b1 a1))
         H1 (if (neg? H) (+ H 360.0) H)
         C1 (m/hypot-sqrt a1 b1)
         C2 (m/hypot-sqrt a2 b2)
         dC (- C1 C2)
         dL (- L1 L2)
         da (- a1 a2)
         db (- b1 b2)
         dH (- (+ (m/sq da) (m/sq db)) (m/sq dC))
         C12 (* C1 C1)
         C14 (* C12 C12)
         F (m/sqrt (/ C14 (+ C14 1900.0)))
         T (if (<= 164.0 H1 345.0)
             (+ 0.56 (m/abs (* 0.2 (m/cos (m/radians (+ H1 168.0))))))
             (+ 0.36 (m/abs (* 0.4 (m/cos (m/radians (+ H1 35.0)))))))
         SL (if (< L1 16.0)
              0.511
              (/ (* 0.040975 L1)
                 (inc (* 0.01765 L1))))
         SC (+ 0.638 (/ (* 0.0638 C1)
                        (inc (* 0.0131 C1))))
         SH (* SC (inc (* F (dec T))))]
     (m/sqrt (+ (m/sq (/ dL (* l SL))) (m/sq (/ dC (* c SC))) (/ dH (m/sq SH)))))))

(def ^{:deprecated "Use delta-E*-CMC"} delta-e-cmc delta-E*-CMC)

(defn delta-E-z
  "ΔE* calculated in JAB color space."
  {:metadoc/categories #{:dist}}
  (^double [c1 c2] (delta-E-z c1 c2 nil))
  (^double [c1 c2 {:keys [colorspace]
                   :or {colorspace :JAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)
         J1 (.x c1)
         J2 (.x c2)
         a1 (.y c1)
         a2 (.y c2)
         b1 (.z c1)
         b2 (.z c2)
         C1 (m/hypot-sqrt a1 b1)
         C2 (m/hypot-sqrt a2 b2)
         h1 (m/atan2 b1 a1)
         h2 (m/atan2 b2 a2)]
     (m/hypot-sqrt (- J2 J1)
                   (- C2 C1)
                   (* 2.0 (m/sqrt (* C1 C2)) (m/sin (* 0.5 (- h2 h1))))))))

(def ^{:deprecated "Use delta-e-jab"} delta-e-jab delta-E-z)

(def ^{:private true :const true :tag 'long} p25_7 (* 25 25 25 25 25 25 25))
(def ^{:private true :const true :tag 'double} r30 (m/radians 30.0))
(def ^{:private true :const true :tag 'double} r6 (m/radians 6.0))
(def ^{:private true :const true :tag 'double} r63 (m/radians 63.0))

(defn- de2000p7
  ^double [^double v]
  (let [v2 (* v v)
        v4 (* v2 v2)
        v7 (* v4 v2 v)]
    (m/sqrt (/ v7 (+ v7 p25_7)))))

(defn delta-E*-2000
  "ΔE* color difference, CIE 2000

  http://www2.ece.rochester.edu/~gsharma/ciede2000/ciede2000noteCRNA.pdf"
  (^double [c1 c2] (delta-E*-2000 c1 c2 nil))
  (^double [c1 c2 {:keys [^double l ^double c ^double h colorspace]
                   :or {l 1.0 c 1.0 h 1.0 colorspace :LAB}}]
   (let [to (first (colorspaces colorspace))
         ^Vec4 c1 (to c1)
         ^Vec4 c2 (to c2)
         C1* (m/hypot-sqrt (.y c1) (.z c1))
         C2* (m/hypot-sqrt (.y c2) (.z c2))
         Cm* (* 0.5 (+ C1* C2*))
         G+ (inc (* 0.5 (- 1.0 (de2000p7 Cm*))))
         a1' (* G+ (.y c1))
         a2' (* G+ (.y c2))
         C1' (m/hypot-sqrt a1' (.z c1))
         C2' (m/hypot-sqrt a2' (.z c2))
         h1' (m/degrees (m/atan2 (.z c1) a1'))
         h1' (if (neg? h1') (+ h1' 360.0) h1')
         h2' (m/degrees (m/atan2 (.z c2) a2'))
         h2' (if (neg? h2') (+ h2' 360.0) h2')
         dL' (- (.x c2) (.x c1))
         dC' (- C2' C1')
         diffh' (- h2' h1')
         adiffh' (m/abs diffh')
         C1'C2' (* C1' C2')
         dh' (m/radians (double (cond
                                  (zero? C1'C2') 0.0
                                  (<= adiffh' 180.0) diffh'
                                  (> diffh' 180.0) (- diffh' 360.0)
                                  :else (+ diffh' 360.0))))
         dH' (* 2.0 (m/sqrt C1'C2') (m/sin (* 0.5 dh')))
         Lm' (* 0.5 (+ (.x c1) (.x c2)))
         Cm' (* 0.5 (+ C1' C2'))
         h1'+h2' (+ h1' h2')
         ^double hm' (cond
                       (zero? C1'C2') h1'+h2'
                       (<= adiffh' 180.0) (* 0.5 h1'+h2')
                       (< h1'+h2' 360.0) (* 0.5 (+ h1'+h2' 360.0))
                       :else (* 0.5 (- h1'+h2' 360.0)))
         hm'r (m/radians hm')
         T (+ 1.0
              (* -0.17 (m/cos (- hm'r r30)))
              (* 0.24 (m/cos (* 2.0 hm'r)))
              (* 0.32 (m/cos (+ (* 3.0 hm'r) r6)))
              (* -0.2 (m/cos (- (* 4.0 hm'r) r63))))
         dtheta (* r30 (m/exp (- (m/sq (/ (- hm' 275.0) 25.0)))))
         Rc (* 2.0 (de2000p7 Cm'))
         Lm'-50sq (m/sq (- Lm' 50.0))
         Sl (inc (/ (* 0.015 Lm'-50sq)
                    (m/sqrt (+ 20.0 Lm'-50sq))))
         Sc (inc (* 0.045 Cm'))
         Sh (inc (* 0.015 Cm' T))
         Rt (- (* (m/sin (* 2.0 dtheta)) Rc))
         l' (/ dL' (* l Sl))
         c' (/ dC' (* c Sc))
         h' (/ dH' (* h Sh))]
     (m/sqrt (+ (m/sq l') (m/sq c') (m/sq h')
                (* Rt c' h'))))))

(defn contrast-ratio
  "WCAG contrast ratio.

  Based on YUV luma."
  {:metadoc/categories #{:dist}}
  ^double [c1 c2]
  (let [l1 (/ (relative-luma c1) 255.0)
        l2 (/ (relative-luma c2) 255.0)]
    (if (> l1 l2)
      (/ (+ l1 0.05) (+ l2 0.05))
      (/ (+ l2 0.05) (+ l1 0.05)))))

(defn- nd-lab-interval
  "Minimal difference values"
  [^double s ^double p]
  (v/mult (Vec3. (+ 10.16 (/ 1.5 s))
                 (+ 10.68 (/ 3.08 s))
                 (+ 10.70 (/ 5.74 s))) p))

(defn noticable-different?
  "Returns noticable difference (true/false) between colors.

  Defined in: https://research.tableau.com/sites/default/files/2014CIC_48_Stone_v3.pdf
  
  Implementation from: https://github.com/connorgr/d3-jnd/blob/master/src/jnd.js"
  {:metadoc/categories #{:dist}}
  ([c1 c2] (noticable-different? c1 c2 nil))
  ([c1 c2 {:keys [^double s ^double p colorspace]
           :or {s 0.1 p 0.5 colorspace :LAB}} ]
   (let [to (first (colorspaces colorspace))
         c1 (to c1)
         c2 (to c2)
         ^Vec4 diff (v/abs (v/sub c1 c2))
         ^Vec3 nd (nd-lab-interval s p)]
     (or (>= (.x diff) (.x nd))
         (>= (.y diff) (.y nd))
         (>= (.z diff) (.z nd))))))

(defn nearest-color
  "Find nearest color from a set. Input: distance function (default euclidean), list of target colors and source color."
  {:metadoc/categories #{:dist}}
  ([pal c dist-fn]
   (let [s (count pal)
         c (pr/to-color c)]
     (loop [i (int 0)
            currc c
            currdist Double/MAX_VALUE]
       (if (< i s)
         (let [c1 (nth pal i)
               dist (m/abs (double (dist-fn c c1)))]
           (recur (unchecked-inc i)
                  (if (< dist currdist) c1 currc)
                  (if (< dist currdist) dist currdist)))
         currc))))
  ([pal c] (nearest-color pal c v/dist))
  ([pal] (partial nearest-color pal)))

(defn average
  "Average colors in given `colorspace` (default: `:sRGB`)"
  {:metadoc/categories #{:interp}}
  ([cs colorspace]
   (let [[to from] (colorspaces colorspace)]
     (from (v/average-vectors (map to cs)))))
  ([cs]
   (v/average-vectors (map pr/to-color cs))))

(defn weighted-average
  "Average colors with weights in given `colorspace` (default: `:sRGB`)"
  {:metadoc/categories #{:interp}}
  ([cs weights colorspace]
   (let [[to from] (colorspaces colorspace)]
     (from (v/div (reduce v/add (map #(v/mult (to %1) %2) cs weights)) (reduce m/+ weights)))))
  ([cs weights]
   (v/div (reduce v/add (map #(v/mult (pr/to-color %1) %2) cs weights)) (reduce m/+ weights))))

(defn lerp
  "Linear interpolation of two colors.

  See also [[lerp+]], [[lerp-]], [[gradient]] and [[mix]]"
  {:metadoc/categories #{:interp}}
  ([c1 c2 colorspace ^double t]
   (let [[to from] (colorspaces colorspace)]
     (from (lerp (to c1) (to c2) t))))
  ([c1 c2] (lerp c1 c2 0.5))
  ([c1 c2 ^double t]
   (v/interpolate (pr/to-color c1) (pr/to-color c2) t)))

(defn lerp-
  "Linear interpolation of two colors conserving luma of the first color.

  Amount: strength of the blend (defaults to 0.5)

  See also [[lerp+]]."
  {:metadoc/categories #{:interp}}
  ([c1 c2 colorspace ^double amount]
   (let [[to from] (colorspaces colorspace)
         cc1 (to c1)
         cc2 (to c2)
         res (from (lerp cc1 cc2 amount))]
     (set-channel res :LAB 0 (ch0 (to-LAB c1)))))
  ([c1 c2] (lerp- c1 c2 0.5))
  ([c1 c2 ^double amount]
   (let [res (lerp c1 c2 amount)]
     (set-channel res :LAB 0 (ch0 (to-LAB c1))))))

(defn lerp+
  "Linear interpolation of two colors conserving luma of the second color.

  Amount: strength of the blend (defaults to 0.5).

  See also [[lerp-]]."
  ([c1 c2 colorspace ^double amount]
   (lerp- c2 c1 colorspace (- 1.0 amount)))
  ([c1 c2] (lerp- c1 c2 0.5))
  ([c1 c2 ^double amount]
   (lerp- c2 c1 (- 1.0 amount))))

(defn- mix-interpolator
  ^double [^double a ^double b ^double t]
  (m/sqrt (+ (* a a (- 1.0 t))
             (* b b t))))

(defn mix
  "Mix colors in given optional `colorspace` (default: `:RGB`) and optional ratio (default: 0.5).

  chroma.js way"
  {:metadoc/categories #{:interp}}
  (^Vec4 [c1 c2 colorspace ^double t]
   (let [[to from] (colorspaces* colorspace)]
     (from (mix (to c1) (to c2) t))))
  (^Vec4 [c1 c2] (mix c1 c2 0.5))
  (^Vec4 [c1 c2 ^double t]
   (let [^Vec4 c1 (pr/to-color c1)
         ^Vec4 c2 (pr/to-color c2)]
     (Vec4. (mix-interpolator (.x c1) (.x c2) t)
            (mix-interpolator (.y c1) (.y c2) t)
            (mix-interpolator (.z c1) (.z c2) t)
            (m/mlerp (.w c1) (.w c2) t)))))

(defn mixbox
  "Pigment based color mixing.

  https://github.com/scrtwpns/mixbox"
  (^Vec4 [col1 col2] (mixbox col1 col2 0.5))
  (^Vec4 [col1 col2 ^double t]
   (let [^int c1 (pack col1)
         ^int c2 (pack col2)]
     (pr/to-color (Mixbox/lerp c1 c2 (float t))))))

(defn negate
  "Negate color (subract from 255.0)"
  (^Vec4 [c] (negate c false))
  (^Vec4 [c alpha?]
   (let [^Vec4 c (pr/to-color c)]
     (Vec4. (- 255.0 (.x c))
            (- 255.0 (.y c))
            (- 255.0 (.z c))
            (if alpha? (- 255.0 (.w c)) (.w c))))))

;; https://github.com/ProfJski/ArtColors/blob/master/RYB.cpp#L230
(defn mixsub
  "Subtractive color mix in given optional `colorspace` (default: `:RGB`) and optional ratio (default: 0.5)"
  {:metadoc/categories #{:interp}}
  (^Vec4 [c1 c2 colorspace ^double t]
   (let [[to from] (colorspaces colorspace)]
     (from (mixsub (to c1) (to c2) t))))
  (^Vec4 [c1 c2] (mixsub c1 c2 0.5))
  (^Vec4 [c1 c2 ^double t]
   (let [c1 (pr/to-color c1)
         c2 (pr/to-color c2)
         mixed (lerp c1 c2 t)
         
         c (negate c1)
         d (negate c2)
         ^Vec4 f (set-alpha (v/clamp (v/sub (v/sub (Vec4. 255.0 255.0 255.0 255.0) c) d) 0.0 255.0) (alpha mixed))
         ;; normalized distance: opaque white - transparent black
         cd (* 4.0 t (- 1.0 t) (/ (v/dist c1 c2) 510.0))]
     (lerp mixed f cd))))

(declare gradient)

(def ^:private vec4-one (Vec4. 1.0 1.0 1.0 1.0))
(defn tinter
  "Creates fn to tint color using other color(s).

  `tint-color`"
  {:metadoc/categories #{:pal}}
  ([tint-color]
   (let [tint (v/add (pr/to-color tint-color) vec4-one)]
     (fn [c]
       (let [c (v/add (pr/to-color c) vec4-one)]
         (clamp (v/mult (v/sqrt (v/div (v/emult c tint) 65536.0)) 255.0)))))))

(defn reduce-colors
  "Reduce colors using k-means++ (or fuzzy-kmeans) clustering in given `colorspace` (default `:RGB`).

  Use for long sequences (for example to generate palette from image)."
  {:metadoc/categories #{:pal}}
  ([xs number-of-colors] (reduce-colors xs number-of-colors nil))
  ([xs number-of-colors {:keys [fuzzy? colorspace]
                         :or {fuzzy? true}
                         :as options}]
   (let [[to from] (if colorspace (colorspaces* colorspace) [identity identity])
         clustering-fn (if fuzzy? cl/fuzzy-kmeans cl/kmeans++)]     
     (sort-by luma ;; sort by brightness
              (for [{:keys [data]} (clustering-fn (map to xs) (assoc options :clusters number-of-colors))]
                (->> (map pack data) ;; pack colors into integers
                     (stat/modes) ;; find colors which appears most often
                     (map (comp pr/to-color unchecked-long)) ;; convert back to colors
                     (v/average-vectors) ;; average vectors if necessary
                     (from))))))) ;; convert back to RGB

;; colors

(defn whiten
  "Change color towards white.

  Works in HSB color space. Default amount is set to 0.2 and changes W channel by this amount.

  See [[blacken]]."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (whiten col 0.2))
  (^Vec4 [col ^double amt]
   (let [c (to-HWB col)]
     (clamp (from-HWB (Vec4. (.x c) (m/constrain (+ (.y c) amt) 0.0 1.0) (.z c) (.w c)))))))

(defn blacken
  "Change color towards black.

  Works in HSB color space. Default amount is set to 0.2 and changes B channel by this amount."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (blacken col 0.2))
  (^Vec4 [col ^double amt]
   (let [c (to-HWB col)]
     (clamp (from-HWB (Vec4. (.x c) (.y c) (m/constrain (+ (.z c) amt) 0.0 1.0) (.w c)))))))

(defn brighten
  "Change luma for givent color by given amount.

  Works in LAB color space. Default amount is 1.0 and means change luma in LAB of 18.0.

  See [[darken]]."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (brighten col 1.0))
  (^Vec4 [col ^double amt]
   (let [c (to-LAB col)]
     (clamp (from-LAB (Vec4. (max 0.0 (+ (.x c) (* 18.0 amt))) (.y c) (.z c) (.w c)))))))

(defn darken
  "Change luma for givent color by given amount.
  
  Works in LAB color space. Default amount is 1.0 and means change luma in LAB of -18.0.

  See [[brighten]]."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (brighten col -1.0))
  (^Vec4 [col ^double amt] (brighten col (- amt))))

(defn saturate
  "Change color saturation in LCH color space."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (saturate col 1.0))
  (^Vec4 [col ^double amt]
   (let [c (to-LCH col)
         ns (max 0.0 (+ (.y c) (* 18.0 amt)))]
     (clamp (from-LCH (Vec4. (.x c) ns (.z c) (.w c)))))))

(defn desaturate
  "Change color saturation in LCH color space."
  {:metadoc/categories meta-ops}
  (^Vec4 [col] (saturate col -1.0))
  (^Vec4 [col ^double amt] (saturate col (- amt))))

(defn adjust
  "Adjust (add) given value to a chosen channel. Works with any color space."
  {:metadoc/categories meta-ops}
  (^Vec4 [col colorspace ^long channel ^double value]
   (let [[to from] (colorspaces colorspace)
         ^Vec4 c (to col)]
     (from (case channel
             0 (Vec4. (+ value (.x c)) (.y c) (.z c) (.w c))
             1 (Vec4. (.x c) (+ value (.y c)) (.z c) (.w c))
             2 (Vec4. (.x c) (.y c) (+ value (.z c)) (.w c))
             3 (Vec4. (.x c) (.y c) (.z c) (+ value (.w c)))
             c))))
  (^Vec4 [col ^long channel ^double value]
   (let [^Vec4 c (pr/to-color col)]
     (case channel
       0 (Vec4. (+ value (.x c)) (.y c) (.z c) (.w c))
       1 (Vec4. (.x c) (+ value (.y c)) (.z c) (.w c))
       2 (Vec4. (.x c) (.y c) (+ value (.z c)) (.w c))
       3 (Vec4. (.x c) (.y c) (.z c) (+ value (.w c)))
       c))))

(defn modulate
  "Modulate (multiply) chosen channel by given amount. Works with any color space."
  {:metadoc/categories meta-ops}
  (^Vec4 [col colorspace ^long channel ^double amount]
   (let [[to from] (colorspaces colorspace)
         ^Vec4 c (to col)]
     (from (case channel
             0 (Vec4. (* amount (.x c)) (.y c) (.z c) (.w c))
             1 (Vec4. (.x c) (* amount (.y c)) (.z c) (.w c))
             2 (Vec4. (.x c) (.y c) (* amount (.z c)) (.w c))
             3 (Vec4. (.x c) (.y c) (.z c) (* amount (.w c)))
             c))))
  (^Vec4 [col ^long channel ^double amount]
   (let [^Vec4 c (pr/to-color col)]
     (case channel
       0 (Vec4. (* amount (.x c)) (.y c) (.z c) (.w c))
       1 (Vec4. (.x c) (* amount (.y c)) (.z c) (.w c))
       2 (Vec4. (.x c) (.y c) (* amount (.z c)) (.w c))
       3 (Vec4. (.x c) (.y c) (.z c) (* amount (.w c)))
       c))))

(defn adjust-temperature
  "Adjust temperature of color.

  Default amount: 0.35
  
  See [[temperature]] and [[lerp+]]."
  {:metadoc/categories meta-ops}
  ([c temp] (adjust-temperature c temp 0.35))
  ([c temp amount]
   (let [t (temperature temp)]
     (lerp+ (pr/to-color c) t amount))))

;;

(defn- interpolated-gradient
  [palette-or-gradient-name {:keys [colorspace interpolation domain to? from?]
                             :or {colorspace :RGB
                                  to? true from? true
                                  interpolation :linear}}]
  (let [[to from] (colorspaces colorspace)
        to (if to? to identity)
        from (if from? from identity)
        cpalette (map (comp to pr/to-color) palette-or-gradient-name)]
    (if (and (keyword? interpolation)
             (contains? e/easings-list interpolation))

      ;; easings
      (let [c1 (first cpalette)
            c2 (second cpalette)
            easing (e/easings-list interpolation)]
        (fn ^Vec4 [^double t]
          (v/fmap (from (v/interpolate c1 c2 (easing t))) clamp255)))

      ;; interpolation
      (let [r (or domain 
                  (map (fn [^long v] (m/mnorm v 0.0 (dec (count cpalette)) 0.0 1.0)) (range (count cpalette))))
            c0 (map pr/red cpalette)
            c1 (map pr/green cpalette)
            c2 (map pr/blue cpalette)
            c3 (map pr/alpha cpalette)
            ifn (if (keyword? interpolation) (partial i/interpolation interpolation) interpolation)
            i0 (ifn r c0)
            i1 (ifn r c1)
            i2 (ifn r c2)
            i3 (ifn r c3)] 
        (fn ^Vec4 [^double t]
          (let [ct (m/constrain t 0.0 1.0)]
            (v/fmap (from (v/vec4 (i0 ct)
                                  (i1 ct)
                                  (i2 ct)
                                  (i3 ct))) clamp255)))))))

(def ^{:metadoc/categories #{:gr}
     :doc "Cubehelix gradient generator from two colors."}
  gradient-cubehelix #(interpolated-gradient % {:colorspace :Cubehelix :to? false :interpolation :linear}))

(defonce ^{:private true} basic-gradients-delay
  (delay (merge (into {} (for [[k v] {:rainbow1              [[0.5 0.5 0.5] [0.5 0.5 0.5] [1.0 1.0 1.0] [0 0.3333 0.6666]]
                                      :rainbow2              [[0.5 0.5 0.5] [0.666 0.666 0.666] [1.0 1.0 1.0] [0 0.3333 0.6666]]
                                      :rainbow3              [[0.5 0.5 0.5] [0.75 0.75 0.75] [1.0 1.0 1.0] [0 0.3333 0.6666]]
                                      :rainbow4              [[0.5 0.5 0.5] [1 1 1] [1.0 1.0 1.0] [0 0.3333 0.6666]]
                                      :yellow-magenta-cyan   [[1 0.5 0.5] [0.5 0.5 0.5] [0.75 1.0 0.6666] [0.8 1.0 0.3333]]
                                      :orange-blue           [[0.5 0.5 0.5] [0.5 0.5 0.5] [0.8 0.8 0.5] [0 0.2 0.5]]
                                      :green-magenta         [[0.6666 0.5 0.5] [0.5 0.6666 0.5] [0.6666 0.666 0.5] [0.2 0.0 0.5]]
                                      :green-red             [[0.5 0.5 0] [0.5 0.5 0] [0.5 0.5 0] [0.5 0.0 0]]
                                      :green-cyan            [[0.0 0.5 0.5] [0 0.5 0.5] [0.0 0.3333 0.5] [0.0 0.6666 0.5]]
                                      :yellow-red            [[0.5 0.5 0] [0.5 0.5 0] [0.1 0.5 0] [0.0 0.0 0]]
                                      :blue-cyan             [[0.0 0.5 0.5] [0 0.5 0.5] [0.0 0.5 0.3333] [0.0 0.5 0.6666]]
                                      :red-blue              [[0.5 0 0.5] [0.5 0 0.5] [0.5 0 0.5] [0 0 0.5]]
                                      :yellow-green-blue     [[0.650 0.5 0.310] [-0.650 0.5 0.6] [0.333 0.278 0.278] [0.660 0.0 0.667]]
                                      :blue-white-red        [[0.660 0.56 0.680] [0.718 0.438 0.720] [0.520 0.8 0.520] [-0.430 -0.397 -0.083]]
                                      :cyan-magenta          [[0.610 0.498 0.650] [0.388 0.498 0.350] [0.530 0.498 0.620] [3.438 3.012 4.025]]
                                      :yellow-purple-magenta [[0.731 1.098 0.192] [0.358 1.090 0.657] [1.077 0.360 0.328] [0.965 2.265 0.837]]
                                      :green-blue-orange     [[0.892 0.725 0.000] [0.878 0.278 0.725] [0.332 0.518 0.545] [2.440 5.043 0.732]]
                                      :orange-magenta-blue   [[0.821 0.328 0.242] [0.659 0.481 0.896] [0.612 0.340 0.296] [2.820 3.026 -0.273]]
                                      :blue-magenta-orange   [[0.938 0.328 0.718] [0.659 0.438 0.328] [0.388 0.388 0.296] [2.538 2.478 0.168]]
                                      :magenta-green         [[0.590 0.811 0.120] [0.410 0.392 0.590] [0.940 0.548 0.278] [-4.242 -6.611 -4.045]]}]
                           [k (apply iq-gradient v)])) ;; thi.ng presets
                {:iq-1 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 1.0 1.0 1.0)
                                    (Vec3. 0.0 0.33 0.67))
                 :iq-2 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 1.0 1.0 1.0)
                                    (Vec3. 0.0 0.1 0.2))
                 :iq-3 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 1.0 1.0 1.0)
                                    (Vec3. 0.3 0.2 0.2))
                 :iq-4 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 1.0 1.0 0.5)
                                    (Vec3. 0.8 0.9 0.3))
                 :iq-5 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 1.0 0.7 0.4)
                                    (Vec3. 0.0 0.15 0.2))
                 :iq-6 (iq-gradient (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 0.5 0.5 0.5)
                                    (Vec3. 2.0 1.0 0.0)
                                    (Vec3. 0.5 0.2 0.25))
                 :iq-7 (iq-gradient (Vec3. 0.8 0.5 0.4)
                                    (Vec3. 0.2 0.4 0.2)
                                    (Vec3. 2.0 1.0 1.0)
                                    (Vec3. 0.0 0.25 0.25))
                 :cubehelix (gradient-cubehelix [(Vec4. 300.0 0.5 0.0 255.0) (Vec4. -240 0.5 1.0 255.0)])
                 :warm (gradient-cubehelix [(Vec4. -100.0 0.75 0.35 255.0) (Vec4. 80.0 1.5 0.8 255.0)])
                 :cool (gradient-cubehelix [(Vec4. 260.0 0.75 0.35 255.0) (Vec4. 80.0 1.5 0.8 255.0)])
                 :rainbow (fn [^double t] (let [ts (m/abs (- t 0.5))]
                                           (from-Cubehelix (Vec4. (- (* t 360.0) 100.0)
                                                                  (- 1.5 (* 1.5 ts))
                                                                  (- 0.8 (* 0.9 ts))
                                                                  255.0))))
                 :black-body (fn [^double t] (temperature (m/mlerp 1000 15000 t)))})))

(defn- get-gradient-from-file-
  [k]
  (let [g (get-palette-or-gradient "gradients/" k)]
    (if (map? g)
      (interpolated-gradient (:c g) {:domain (:p g)})
      (interpolated-gradient g {}))))

(defonce ^:private get-gradient-from-file (memoize get-gradient-from-file-))

(defn- get-gradient
  [k]
  (if (namespace k)
    (get-gradient-from-file k)
    (@basic-gradients-delay k)))

(defonce ^:private gradients-list-
  (delay (let [g (set (concat (keys @basic-gradients-delay)
                              (read-edn "gradients/all-gradients.edn")))]
           {:set g
            :seq (vec (sort g))})))

(defn gradient
  "Return gradient function.
  
  Grandient function accepts value from 0 to 1 and returns interpolated color.

  Parameters;

  * `palette-or-gradient-name` - name of the [predefined gradient](../static/gradients.html) or palette (seq of colors).
  * (optional) additional parameters map:
      * `:colorspace` - operate in given color space. Default: `:RGB`.
      * `:interpolation` - interpolation name or interpolation function. Interpolation can be one of [[interpolators-1d-list]] or [[easings-list]]. When easings is used, only first two colors are interpolated. Default: `:linear`.
      * `:to?` - turn on/off conversion to given color space. Default: `true`.
      * `:from?` - turn on/off conversion from given color space. Default: `true`.
      * `:domain` - interpolation domain as seq of values for each color.

  When called without parameters random gradient is returned."
  {:metadoc/categories #{:gr}}
  ([] (gradient (rand-nth (:seq @gradients-list-))))
  ([palette-or-gradient-name] (gradient palette-or-gradient-name {}))
  ([palette-or-gradient-name options]
   (cond (keyword? palette-or-gradient-name) (get-gradient palette-or-gradient-name)
         (and (= (:interpolation options) :iq)
              (seqable? palette-or-gradient-name)
              (every? valid-color? palette-or-gradient-name)) (apply iq-gradient palette-or-gradient-name)       
         :else (interpolated-gradient palette-or-gradient-name options))))

(defn merge-gradients
  "Combine two gradients, optionally select `midpoint` (0.5 by default).

  Resulting gradient has colors from `g1` for values lower than `midpoint` and from `g2` for values higher than `midpoint`"
  {:metadoc/categories #{:gr}}
  ([g1 g2 ^double midpoint]
   (fn [^double t]
     (if (< t midpoint)
       (g1 (m/norm t 0.0 midpoint))
       (g2 (m/norm t midpoint 1.0)))))
  ([g1 g2] (merge-gradients g1 g2 0.5)))

;;

(defonce ^:private palettes-list-
  (delay (let [p (set (read-edn "palettes/all-palettes.edn"))]
           {:set p
            :seq (vec (sort-by keyword p))})))

(defn- get-palette-
  [k]
  (when (contains? (:set @palettes-list-) k)
    (mapv pr/to-color (get-palette-or-gradient "palettes/" k))))

(defonce ^:private get-palette (memoize get-palette-))

(defn palette
  "Get palette.

  If argument is a keyword, returns one from palette presets.
  If argument is a number, returns one from colourlovers palettes.
  If argument is a gradient, returns 5 or `number-of-colors` samples.

  If called without argument, random palette is returned
  
  Optionally you can pass number of requested colors and other parameters as in [[resample]]"
  {:metadoc/categories #{:pal}}
  ([] (palette (rand-nth (:seq @palettes-list-))))
  ([p]
   (cond
     (or (keyword? p)
         (integer? p)) (get-palette p)
     (fn? p) (palette p 5)
     :else (vec p)))
  ([p number-of-colors] (palette p number-of-colors {}))
  ([p ^long number-of-colors gradient-params]
   (vec (if (fn? p)
          (if (m/one? number-of-colors)
            [(if-let [cs (:colorspace gradient-params)]
               (average (m/sample p 100) cs)
               (average (m/sample p 100)))]
            (m/sample p number-of-colors))
          (if (m/one? number-of-colors)
            [(if-let [cs (:colorspace gradient-params)]
               (average (palette p) cs)
               (average (palette p)))]
            (palette (gradient (palette p) gradient-params) number-of-colors))))))

(defn- find-gradient-or-palette
  ([lst] (:seq @lst))
  ([lst regex] (filter (comp (partial re-find regex) str) (:seq @lst)))
  ([lst group regex] (filter (fn [k]
                               (and (= (namespace k) group)
                                    (re-find regex (name k)))) (:seq @lst))))

(def find-gradient (partial find-gradient-or-palette gradients-list-))
(def find-palette (partial find-gradient-or-palette palettes-list-))

(defn resample
  "Resample palette.

  Internally it's done by creating gradient and sampling back to colors. You can pass [[gradient]] parameters like colorspace, interpolator name and domain."
  {:metadoc/categories #{:pal}}
  ([pal number-of-colors] (resample pal number-of-colors {}))
  ([pal number-of-colors gradient-params] (palette pal number-of-colors gradient-params)))

(defn correct-luma
  "Create palette or gradient with corrected luma to be linear.

  See [here](https://www.vis4.net/blog/2013/09/mastering-multi-hued-color-scales/#combining-bezier-interpolation-and-lightness-correction)"
  {:metadoc/categories #{:pal :gr}}
  ([palette-or-gradient] (correct-luma palette-or-gradient {}))
  ([palette-or-gradient gradient-params]
   (if (fn? palette-or-gradient) ;; gradient
     (let [l0 (ch0 (to-LAB (palette-or-gradient 0.0)))
           l1 (ch0 (to-LAB (palette-or-gradient 1.0)))
           xs (m/slice-range 0.0 1.0 200)
           ls (map (fn [^double v] (ch0 (to-LAB (palette-or-gradient v)))) xs)
           i (li/linear ls xs)]
       (fn ^Vec4 [^double t] (palette-or-gradient (i (m/lerp l0 l1 t)))))
     (let [n (count palette-or-gradient)
           g (correct-luma (gradient palette-or-gradient gradient-params))]
       (palette g n)))))

;;;

(defn- iq-random-gradient
  "Create random iq gradient."
  {:metadoc/categories #{:gr}}
  []
  (r/randval
   (let [a (v/generate-vec3 (partial r/drand 0.2 0.8))
         b (v/generate-vec3 (partial r/drand 0.2 0.8))
         c (v/generate-vec3 (partial r/drand 2))
         d (v/generate-vec3 r/drand)]
     (iq-gradient a b c d))
   (let [pal (paletton :monochromatic (r/irand 360) {:compl true :preset (rand-nth [:pastels :pastels-med :full :shiny :dark :pastels-mid-dark :dark-neon :darker])})]
     (iq-gradient (first pal) (first (drop 5 pal))))))

(defn random-palette
  "Generate random palette from all collections defined in clojure2d.color namespace."
  {:metadoc/categories #{:gr}}
  []
  (condp clojure.core/> (r/drand)
    0.1 (resample (iq-random-gradient) (r/irand 3 10))
    0.3 (palette (int (rand 500)))
    0.6 (let [p (palette)]
          (if (> (count p) 15) (resample p 15) p))
    0.8 (resample (gradient) (r/irand 3 10))
    (let [h (r/drand 360)
          t (rand-nth [:monochromatic :triad :triad :triad :triad :triad :tetrad :tetrad :tetrad])
          conf {:compl (r/brand 0.6)
                :angle (r/drand 10.0 90.0)
                :adj (r/brand)
                :preset (rand-nth paletton-presets-list)}]
      (paletton t h conf))))

(defn random-gradient
  "Generate random gradient function."
  {:metadoc/categories #{:pal}}
  []
  (let [gpars {:colorspace (r/randval :RGB (rand-nth colorspaces-list))
               :interpolation (r/randval :linear :cubic)}]
    (condp clojure.core/> (r/drand)
      0.1 (iq-random-gradient)
      0.6 (gradient (palette) gpars)
      0.7 (gradient (sort-by luma (paletton :monochromatic (r/drand 360)
                                            {:preset (rand-nth paletton-presets-list)})) gpars)
      (gradient))))

(defonce ^:private thing-presets
  ;; L range and C range
  {:intense-light [[204.0 255.0] [229.5 255.0]]
   :intense-dark  [[51.0 89.25]  [229.5 255.0]]
   :light         [[229.5 255.0] [76.5 178.5]]
   :dark          [[38.25 102.0] [178.5 255.0]]
   :bright        [[204.0 255.0] [191.25 242.25]]
   :weak          [[178.5 255.0] [38.25 76.5]]
   :neutral       [[76.5 178.5]  [63.75 89.25]]
   :fresh         [[204.0 255.0] [102.0 204.0]]
   :soft          [[153.0 229.5] [51.0 76.5]]
   :hard          [[102.0 255.0] [216.75 242.25]]
   :warm          [[102.0 229.5] [153.0 229.5]]
   :cool          [[229.5 255.0] [12.75 51.0]]
   :all           [[0.0 255.0]   [0.0 255.0]]
   :gray-light    [[178.5 255.0] [0.0 0.0]]
   :gray-dark     [[0.0 76.5]    [0.0 0.0]]
   :gray          [[76.5 178.5]  [0.0 0.0]]})

(defonce thing-presets-list (set (keys thing-presets)))
(defonce color-themes (sort (concat thing-presets-list paletton-presets-list)))

(defn- random-channel-value
  ^double [scheme ^double value]
  (cond
    (number? scheme) (let [v (double scheme)]
                       (r/drand (- value v)
                                (+ value v)))
    (sequential? scheme) (let [[mn mx] scheme]
                           (r/drand mn mx))
    :else value))

(defn- apply-LCH-theme
  [color [L C H]]
  (let [^Vec4 c (to-LCH* color)]
    (set-alpha (from-LCH* (map random-channel-value [L C H] c)) (.w c))))

(defn apply-theme
  "Apply theme to the color, see [[color-themes]] for names.

  Generates random color similar to provided one.
  All operations are done in LSH* color space (normalized to have values between 0 and 255).

  Color theme can be one of:

  * keyword - predefined color theme is used
  * [L C H] - randomization scheme of given channel, which can be:
      - [min-value max-value] - uniform random value from given range
      - a number - [channel-value, channel+value] range is used for random selection
      - nil, or anything else - keep original channel value"
  [color color-theme]
  (clamp (if (keyword color-theme)
           (if (thing-presets-list color-theme)
             (let [[[l1 l2] [c1 c2]] (thing-presets color-theme)
                   ^Vec4 c (to-LCH* color)]
               (from-LCH* (Vec4. (r/drand l1 l2) (r/drand c1 c2) (.z c) (.w c))))
             (let [g (->> {:preset color-theme}
                          (paletton :monochromatic (hue-paletton color))
                          (sort-by luma)
                          (gradient))]
               (set-alpha (g (r/drand)) (alpha color))))
           (apply-LCH-theme color color-theme))))

(defn random-color
  "Generate random color.

  Optionally color theme or alpha can be provided.

  List of possible color themes is stored in `color-themes` var. These are taken from thi.ng and paletton."
  {:metadoc/categories #{:pal}}
  ([color-theme alpha]
   (clamp (if (thing-presets-list color-theme)
            (let [[[l1 l2] [c1 c2]] (thing-presets color-theme)]
              (from-LCH* [(r/drand l1 l2) (r/drand c1 c2) (r/drand 255.0) alpha]))
            (set-alpha (rand-nth (paletton :monochromatic (r/drand 360.0) {:preset color-theme})) alpha))))
  ([alpha-or-color-theme]
   (if (keyword? alpha-or-color-theme)
     (random-color alpha-or-color-theme 255.0)
     (set-alpha (random-color) alpha-or-color-theme)))
  ([] (r/randval 0.2 (rand-nth (named-colors-list))
                 (r/randval 0.5
                            (rand-nth (random-palette))
                            (Vec4. (r/drand 255.0) (r/drand 255.0) (r/drand 255.0) 255.0)))))

(defn fe-color-matrix
  "Create a feColorMatrix operator."
  [[^double r1 ^double r2 ^double r3 ^double r4 ^double r5
    ^double g1 ^double g2 ^double g3 ^double g4 ^double g5
    ^double b1 ^double b2 ^double b3 ^double b4 ^double b5
    ^double a1 ^double a2 ^double a3 ^double a4 ^double a5]]
  (fn [c]
    (let [^Vec4 c (pr/to-color c)]
      (clamp (Vec4. (+ (* r1 (.x c)) (* r2 (.y c)) (* r3 (.z c)) (* r4 (.w c)) r5)
                    (+ (* g1 (.x c)) (* g2 (.y c)) (* g3 (.z c)) (* g4 (.w c)) g5)
                    (+ (* b1 (.x c)) (* b2 (.y c)) (* b3 (.z c)) (* b4 (.w c)) b5)
                    (+ (* a1 (.x c)) (* a2 (.y c)) (* a3 (.z c)) (* a4 (.w c)) a5))))))

(defn sepia
  ([^double amount]
   (let [amount (- 1.0 amount)
         a (+ 0.393 (* 0.607 amount)) b (- 0.769 (* 0.769 amount)) c (- 0.189 (* 0.189 amount))
         d (- 0.349 (* 0.349 amount)) e (+ 0.686 (* 0.314 amount)) f (- 0.168 (* 0.168 amount))
         g (- 0.272 (* 0.272 amount)) h (- 0.534 (* 0.534 amount)) i (+ 0.131 (* 0.869 amount))]
     (fe-color-matrix [a b c 0.0 0.0
                       d e f 0.0 0.0
                       g h i 0.0 0.0
                       0.0 0.0 0.0 1.0 0,0]))))

(defn contrast
  ([^double amount]
   (let [intercept (* 127.5 (- 1.0 amount))]
     (fe-color-matrix [amount 0.0 0.0 0.0 intercept
                       0.0 amount 0.0 0.0 intercept
                       0.0 0.0 amount 0.0 intercept
                       0.0 0.0 0.0 1.0 0.0]))))

(defn exposure
  ([^double amount]
   (fe-color-matrix [amount 0.0 0.0 0.0 0.0
                     0.0 amount 0.0 0.0 0.0
                     0.0 0.0 amount 0.0 0.0
                     0.0 0.0 0.0 1.0 0.0])))

(defn brightness
  ([^double amount]
   (let [amount (* 255.0 amount)]
     (fe-color-matrix [1.0 0.0 0.0 0.0 amount
                       0.0 1.0 0.0 0.0 amount
                       0.0 0.0 1.0 0.0 amount
                       0.0 0.0 0.0 1.0 0.0]))))


(defn saturation
  ([^double amount]
   (let [r+ (+ 0.2126 (* 0.7874 amount))
         r- (- 0.2126 (* 0.2126 amount))
         g+ (+ 0.7152 (* 0.2848 amount))
         g- (- 0.7152 (* 0.7152 amount))
         b+ (+ 0.0722 (* 0.9278 amount))
         b- (- 0.0722 (* 0.0722 amount))]
     (fe-color-matrix [r+ g- b- 0.0 0.0
                       r- g+ b- 0.0 0.0
                       r- g- b+ 0.0 0.0
                       0.0 0.0 0.0 1.0 0.0]))))

(defn hue-rotate
  ([^double angle-degrees]
   (let [a (m/radians angle-degrees)
         sa (m/sin a)
         ca (m/cos a)
         cr+ (+ 0.213 (* 0.787 ca))
         cr- (+ 0.213 (* -0.213 ca))
         cg+ (+ 0.715 (* 0.285 ca))
         cg- (+ 0.715 (* -0.715 ca))
         cb+ (+ 0.072 (* 0.928 ca))
         cb- (+ 0.072 (* -0.072 ca))]
     (fe-color-matrix [(+ cr+ (* -0.213 sa)) (+ cg- (* -0.715 sa)) (+ cb- (* 0.928 sa)) 0.0 0.0
                       (+ cr- (* 0.143 sa)) (+ cg+ (* 0.140 sa)) (+ cb- (* -0.283 sa)) 0.0 0.0
                       (+ cr- (* -0.787 sa)) (+ cg- (* 0.715 sa)) (+ cb+ (* 0.071 sa)) 0.0 0.0
                       0.0 0.0 0.0 1.0 0.0]))))

(defn grayscale
  ([^double amount] (saturation (- 1.0 amount))))

;; Color Vision Deficiency
;; https://github.com/chromelens/chromelens/blob/master/lenses/filters/

(def achromatomaly (fe-color-matrix [0.618,0.320,0.062,0.0,0.0,
                                   0.163,0.775,0.062,0.0,0.0,
                                   0.163,0.320,0.516,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def achromatopsia (fe-color-matrix [0.299,0.587,0.114,0.0,0.0,
                                   0.299,0.587,0.114,0.0,0.0,
                                   0.299,0.587,0.114,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def deuteranomaly (fe-color-matrix [0.8,0.2,0.0,0.0,0.0,
                                   0.258,0.742,0.0,0.0,0.0,
                                   0.0,0.142,0.858,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def deuteranopia  (fe-color-matrix [0.625,0.375,0.0,0.0,0.0,
                                   0.7,0.3,0.0,0.0,0.0,
                                   0.0,0.3,0.7,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def protanomaly   (fe-color-matrix [0.817,0.183,0.0,0.0,0.0,
                                   0.333,0.667,0.0,0.0,0.0,
                                   0.0,0.125,0.875,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def protanopia    (fe-color-matrix [0.567,0.433,0.0,0.0,0.0,
                                   0.558,0.442,0.0,0.0,0.0,
                                   0.0,0.242,0.758,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def tritanomaly   (fe-color-matrix [0.967,0.033,0.0,0.0,0.0,
                                   0.0,0.733,0.267,0.0,0.0,
                                   0.0,0.183,0.817,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))
(def tritanopia    (fe-color-matrix [0.95,0.05,0.0,0.0,0.0,
                                   0.0,0.433,0.567,0.0,0.0,
                                   0.0,0.475,0.525,0.0,0.0,
                                   0.0,0.0,0.0,1.0,0.0]))

;; some utils

(defn- make-line
  [n l]
  (apply list '+
         (remove nil? (map (fn [^double a p]
                             (cond
                               (zero? a) nil
                               (== 1.0 a) (list p n)
                               :else (list '* a (list p n)))) l '[.x .y .z]))))

(defn- matrix-inverse
  ([matrix] (matrix-inverse 'c matrix))
  ([n matrix]
   (let [m (->> matrix
                m/seq->double-double-array
                org.apache.commons.math3.linear.Array2DRowRealMatrix.
                org.apache.commons.math3.linear.MatrixUtils/inverse
                .getData)]
     (for [l m]
       (make-line n l)))))

(defn- test-colors
  "to remove, check ranges"
  [f]
  (loop [cc (int 0)
         mnr (double Integer/MAX_VALUE)
         mxr (double Integer/MIN_VALUE)
         mng (double Integer/MAX_VALUE)
         mxg (double Integer/MIN_VALUE)
         mnb (double Integer/MAX_VALUE)
         mxb (double Integer/MIN_VALUE)]
    (let [r (bit-and 0xff (bit-shift-right cc 16))
          g (bit-and 0xff (bit-shift-right cc 8))
          b (bit-and 0xff cc)
          ^Vec4 res (f (Vec4. r g b 255.0))
          nmnr (if (< (.x res) mnr) (.x res) mnr)
          nmxr (if (> (.x res) mxr) (.x res) mxr)
          nmng (if (< (.y res) mng) (.y res) mng)
          nmxg (if (> (.y res) mxg) (.y res) mxg)
          nmnb (if (< (.z res) mnb) (.z res) mnb)
          nmxb (if (> (.z res) mxb) (.z res) mxb)]
      (when (or (m/invalid-double? (.x res))
                (m/invalid-double? (.y res))
                (m/invalid-double? (.z res)))
        (println "Warning: invalid numbers in " res " for " [r g b]))
      (if (< cc 0x1000000)
        (recur (inc cc) (double nmnr) (double nmxr) (double nmng) (double nmxg) (double nmnb) (double nmxb))
        {:r [nmnr nmxr] :g [nmng nmxg] :b [nmnb nmxb]}))))

(defn- test-reversibility
  ([colorspace]
   (let [[to from] (colorspaces colorspace)]
     (test-reversibility to from)))
  ([to from]
   (for [r (range 256)
         g (range 256)
         b (range 256)
         :let [in (Vec4. r g b 255.0)
               cs (to in)
               rgb (from cs)]
         :when (not= in (lclamp rgb))]
     [r g b cs (lclamp rgb) (v/dist in rgb)])))

(m/unuse-primitive-operators)

(comment (run! (fn [[k [to from]]]
                 (println "---")
                 (println k)
                 (let [f (time (doall (test-reversibility to from)))]
                   (println (take 3 f))))
               (remove (comp #{:Gray} first) colorspaces))
         
         
         )
