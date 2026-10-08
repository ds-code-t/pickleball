# Experimental features

Features that work now but are not recommended and may change.

## Position context words

`below`, `above`, `left of`, and `right of` parse and resolve by comparing element rectangles. The anchor itself is not a match. The edge comparison allows one pixel of slack. Do not treat these words as stable context words. The stable context words are `in`, `from`, `for`, `after`, and `before`.

| Word | Match |
|---|---|
| `below` | the candidate's top is at or below the anchor's bottom, and the rectangles overlap horizontally |
| `above` | the candidate's bottom is at or above the anchor's top, and the rectangles overlap horizontally |
| `left of` | the candidate's right is at or left of the anchor's left, and the rectangles overlap vertically |
| `right of` | the candidate's left is at or right of the anchor's right, and the rectangles overlap vertically |

Horizontal overlap means the candidate's left edge is left of the anchor's right edge and the candidate's right edge is right of the anchor's left edge. Vertical overlap means the candidate's top is above the anchor's bottom and the candidate's bottom is below the anchor's top.

```gherkin
* , below the "Spatial Anchor" Button, click the "Below Target" Button
* , above the "Spatial Anchor" Button, click the "Above Target" Button
* , left of the "Spatial Anchor" Button, click the "Left Target" Button
* , right of the "Spatial Anchor" Button, click the "Right Target" Button
```
