# NASA Deep Star Maps 2020 notice

Prime includes a preprocessed, lossy BC6H copy of `starmap_2020_16k.exr` from
[NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/). The source
image is the 16384×8192 plate carrée celestial map in ICRF/J2000 coordinates,
centered at 0h right ascension with right ascension increasing to the left.
Prime retains its existing linear-sRGB interpretation and converts the source
RGB to D65 linear Rec.2020 before BC6H unsigned-float encoding. The EXR does
not specify primaries or white point; this interpretation is not a claim about
NASA's source colorimetry. No exposure or tone mapping is applied. The original
16K base level is retained; 14 additional mip levels are averaged in linear
Rec.2020 using texel solid-angle weights. Compressed BC6H blocks for all 15 levels
are uploaded directly.

Source SHA-256:
`19a1351f00c386a6e5eec4d67af96d5fc71edf6a1189941579b9498b52e7589a`

The image retains NASA's [Images and Media Usage Guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/)
and the source page's credit requirements. NASA generally makes these media
available for factual computer graphics use with source acknowledgment and
without implying endorsement; identified third-party material retains its own
rights. Prime's GPL license does not relicense the source image. This asset
does not include the separate constellation-boundary or figure TIFF overlays.
The complete source-page credit is retained below.

Please give credit for this item to:

NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2:
[ESA/Gaia/DPAC](https://gea.esac.esa.int/archive/documentation/GDR2/Miscellaneous/sec_credit_and_citation_instructions/).
Constellation figures based on those developed for the IAU by Alan MacRobert
of *Sky and Telescope* magazine (Roger Sinnott and Rick Fienberg).
