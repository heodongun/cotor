# `.interface-design/system.md` Template

```md
# Design System

## Direction
Personality: <one>
Foundation: <neutral family + accent>
Depth: <borders-only | soft-elevation>
Density: <compact | balanced | spacious>

## Tokens
### Spacing
Base: 4px | 8px
Scale: 4, 8, 12, 16, 20, 24, 32, 40, 48

### Radius
xs: 4
sm: 6
md: 8
lg: 12
xl: 16

### Typography
Font: <family>
Scale: 12, 14, 16, 20, 24, 30
Weights: 400, 500, 600, 700

### Colors
Neutral: <token map>
Accent: <token map>
Semantic: success/warning/error/info

### Elevation/Borders
Style: <single strategy>
Border: <token>
Shadow: <token set if used>

### Motion
Duration: 120ms / 180ms / 240ms
Easing: standard / emphasized

## Component Patterns
### Button/Primary
- Height
- Padding
- Radius
- Focus ring
- Disabled behavior

### Input/Default
- Height
- Border
- Placeholder color
- Error state

### Card/Default
- Surface token
- Border/shadow rule
- Padding

### Table
- Row height
- Header style
- Hover/selection behavior
```
