# 013. Dancing Script for typed signatures
Date: 2026-11-06
## Context
Sign PDF lets people type their name as a signature (SPEC §4: "type, choose a script font"). The bundled fonts (IBM Plex Sans, Noto Sans) aren't handwriting fonts. The owner chose between Dancing Script, Great Vibes and Caveat, all under the SIL Open Font License.
## Decision
- Bundle **Dancing Script Regular** (SIL OFL 1.1, licence in `src/main/resources/fonts/DancingScript-OFL.txt`). The owner chose it on 2026-11-06. It's a joined-up script that stays readable small. The static Regular TTF from the font's official repository (googlefonts/DancingScript) is used, rather than the variable-weight version, so it embeds simply.
- A name with letters Dancing Script lacks (e.g. Tshivenda ṱ, ḓ) is written in Noto Sans instead of showing empty boxes.
- Signatures are pictures, transparent around the ink, from drawing, typing or a photo of a signature on paper (`SignatureMaker`). They're placed on the page as shown, so turned pages work. This is a visual signature like signing a printout, not a certificate-based digital signature, as SPEC sets for v1; the tool screen says so.
## Consequences
- The app grows by about 120 KB.
- Only one handwriting style is offered; more could be added later in the same way.
