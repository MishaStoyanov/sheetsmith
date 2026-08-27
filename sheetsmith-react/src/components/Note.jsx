/** A quiet aside: something the screen has to say in words rather than leave to be inferred. */
export default function Note({ children }) {
  return (
    <div style={{ padding: '10px 14px', borderRadius: 10, background: 'var(--surface-2)', border: '1px solid var(--border)', color: 'var(--text-dim)', fontSize: 13, marginBottom: 16, lineHeight: 1.5 }}>
      {children}
    </div>
  );
}
