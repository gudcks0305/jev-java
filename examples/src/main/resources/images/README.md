# Synthetic image smoke fixture

`red-rectangle.png` is a generated 280 × 280 RGB PNG, with a white background
and red pixels at `70 <= x < 210`, `70 <= y < 210`. It contains no user image,
metadata, or external asset. Its geometric edges are approximately
`top=0.25, bottom=0.75, left=0.25, right=0.75`.

The opt-in `ImageExample` sends it to TypeSafe and prints the raw Noul and Box
values. A successful response proves request/response interoperability, not
localization accuracy. See `docs/validation.md` for the observed live result.
