# CleanYourTV UI — themes edition

A home screen for Android TV with six built-in themes (Classic plus five new ones), tuned for Philips Android TVs. It is a fork of
[matheus-caldeira/cleanyourtv-ui](https://github.com/matheus-caldeira/cleanyourtv-ui), itself built on
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv). Everything from those projects is still
here (the debloat tools, lists and the reference launcher in `launcher/`); this fork adds to the second home
screen in `launcher-ui/`.

> **Screenshots:** coming soon. They will show each theme with sample artwork, not real library posters.
> Placeholder: `docs/themes/` (one image per theme).

## Themes

Pick one on the TV: launcher settings → **Home settings → Theme**. **Appearance** (Automatic, Light, Dark)
applies to Classic and Editorial; the other themes are always dark. **App icons** switches the Glass and
Colors tiles between line icons drawn by the app and the apps' own logos.

| Theme | Look |
|---|---|
| **Classic** | The original: light by day and dark after sunset, a blurred picture behind, glass buttons, logo tiles on a glass shelf. |
| **Glass** | Dark aurora gradient, the buttons in one glass capsule, glass tiles with line icons, a glass "resume" bar on the picture, glass menus. |
| **Editorial** | Cream paper by day, black at night, serif titles (Instrument Serif), a rounded picture with its title beside it, two arched posters, app pills. |
| **Colors** | A wide picture with a resume button, colored app tiles, a "Keep watching" row, and a quick settings panel on the Sources button. |
| **Minimal** | Black with a lime accent, a huge clock, monospace captions (JetBrains Mono), the apps as plain names, side-panel menus. |
| **HUD** | Night blue with a faint grid, cyan, cut corners (Michroma), a time-left ring, a queue column, menus as cards linked to their button. |

All themes are drawn on the same layers as the original screen, with no blur and no animation while nothing
moves. Measured on a 2022 Philips OLED (Android TV 11): the same key response and frame pacing for every
theme, and 51 to 85 MB of memory.

## Philips features

Tested on a Philips OLED907 (Android TV 11, board PH1M_EA_9970A). Other Philips sets of the same generation
should behave the same; other brands simply don't show these buttons or ignore them.

- **Ambilight + Hue in one press.** The Ambilight button turns Ambilight + Hue on or off through the TV's own
  Hue service, locally (no cloud, no JointSPACE call from the TV). A ring turns around the button while it is
  on, the real state is read back, and the last choice is restored after power-on (Philips turns the lamps
  back on at every start). Needs "Ambilight+hue" enabled in the TV's Ambilight settings.
- **Sources and Options keys.** An optional accessibility service opens this launcher's Sources and Options
  menus from any app (both keys arrive as keycode 602, scan codes 757 and 641).
- **OLED screensaver guard.** Philips firmware sets the screensaver delay back to "never" at every boot; the
  launcher sets it again at boot and on every Home (`screensaver_minutes`).
- **Clean HDMI-CEC names.** Sources show the device's name ("PlayStation 5") even in standby, without
  Philips' "&TYPE" suffix, and empty ports are hidden. An HDMI input can also be pinned to the app row.
- **Power menu** with standby and restart, and a Picture/TV settings shortcut.

## Optional services

None of these is required; leave the setting empty and the feature stays off.

- **Plex**: a Plex tab with what you are watching and recent additions, from a Plex Media Server on your network.
- **Live TV app**: the app "Live TV" opens instead of the antenna (for example an operator's app).
- **VPN app**: the Wi-Fi button opens a VPN app when one is installed.
- **NAS update service** (`launcher-ui/nas/`): a small Docker service that merges upstream changes, builds the
  app and lets the TV install updates without a computer. Without it, `launcher-ui/update.sh` does the same
  from a computer.
- **Weather and markets** cards (Open-Meteo, public quotes).

## Install

You need a computer with Git Bash or a Unix shell, JDK 17 and the Android SDK (platform 34, build-tools
34.0.0), and network debugging enabled on the TV (Settings → System → About → press "Build" seven times, then
Developer options → Network debugging). Turn it off again when you are done.

1. Clone this repository.
2. Copy `launcher-ui/config.example.properties` to `launcher-ui/config.properties` and fill in what you want
   (every key is optional and commented). **Never commit `config.properties`**: it can hold a Plex token.
3. Build and install, with your TV's address:
   ```
   TV_HOST=<TV_IP> KEY_SERVICE=1 bash launcher-ui/build-and-install.sh
   ```
   `KEY_SERVICE=1` enables the Sources/Options key service; `SCREENSAVER=0` keeps the TV's own screensaver.
4. Press Home on the remote.

To go back to the stock home screen, see [`README.original.md`](README.original.md); the debloat lists and the
restore tool are documented in [`README.cleanyourtv.md`](README.cleanyourtv.md).

The app asks for more than the reference launcher does, and says so here: `INTERNET` (weather, quotes, Plex,
optional update checks), an optional accessibility service (remote keys), `RECEIVE_BOOT_COMPLETED` (screensaver
guard, Ambilight choice) and `REQUEST_INSTALL_PACKAGES` (only used by the optional NAS updates). Build it from
source and read `launcher-ui/app/AndroidManifest.xml` before installing.

## Credits and licences

- [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) and
  [matheus-caldeira/cleanyourtv-ui](https://github.com/matheus-caldeira/cleanyourtv-ui), MIT ([`LICENSE`](LICENSE));
  `launcher-ui/LICENSE.free-my-tv` and `launcher-ui/NOTICE` for the parts that come from Free My TV.
- Fonts, all under the SIL Open Font License 1.1, in `launcher-ui/app/assets/fonts/`: Inter (`OFL.txt`),
  Instrument Serif (`OFL-InstrumentSerif.txt`), JetBrains Mono (`OFL-JetBrainsMono.txt`), Michroma (`OFL-Michroma.txt`).
- Material Symbols Rounded by Google, Apache 2.0 (`launcher-ui/app/assets/symbols/LICENSE-material-symbols.txt`).
- The line icons of the themes are drawn in code (`Icons.java`) and ship under this repository's MIT licence.
- App logos and banners come from the apps installed on the TV at run time; none are included here. Pictures in
  the Plex tab come from your own server.

This repository is not affiliated with Philips, TP Vision, Google, Plex or any app maker. Ambilight and Hue are
trademarks of their owners.
