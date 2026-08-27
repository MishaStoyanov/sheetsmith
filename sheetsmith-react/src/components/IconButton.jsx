import Button from './Button.jsx';

/** A square button holding one glyph. `title` is required: an icon with no name is a guess. */
export default function IconButton({ title, children, style, ...rest }) {
  return (
    <Button
      title={title}
      aria-label={title}
      style={{ width: 32, height: 32, padding: 0, fontSize: 14, ...style }}
      {...rest}
    >
      {children}
    </Button>
  );
}
