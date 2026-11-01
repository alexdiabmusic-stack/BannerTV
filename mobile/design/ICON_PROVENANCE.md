# Android phone icon provenance

The phone launcher uses the existing Banner artwork from
[StadiaTV-iOS center.svg](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/blob/b58b989aa5f9e30b0396ba81ddc73da3dcb7c7ab/MyApp/AppIcon.icon/Assets/center.svg).

- Pinned commit: `b58b989aa5f9e30b0396ba81ddc73da3dcb7c7ab`.
- Original Git blob SHA-1: `906ec207c8a230247088d5a4a1313d345ac2dd35`.
- Generated 512×512 PNG SHA-256:
  `df01499249014464871eee853b3f1dd34881b1de89406015691a1445febeab12`.
- Android resource: `mobile/src/main/res/drawable-nodpi/banner_app_icon.png`.

`mobile/tools/import_ios_icon.py` verifies the original Git blob identity, decodes the sole embedded
PNG and renders the SVG's own 1024-square viewBox into the Android bitmap. It does not redraw the
mark, remove its background, invent variants or copy credentials. With Pillow installed:

```
python3 mobile/tools/import_ios_icon.py /path/to/original/center.svg mobile/src/main/res/drawable-nodpi/banner_app_icon.png
```

The iOS Icon Composer glass/specular treatment is not an Android API and is not reproduced. This
is the pinned flattened artwork, not a newly separated adaptive/monochrome layer set. The existing
Fire TV launcher/banner resources remain unchanged. Launcher/runtime verification is recorded in
ADAPTIVE_PHONE_PARITY_PROGRESS.md; an asset conversion alone is not device verification.
