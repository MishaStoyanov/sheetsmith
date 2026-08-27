import { useEffect } from 'react';

/**
 * A dialog over the page.
 *
 * Escape closes it and a click on the backdrop closes it, because both are what people try first.
 * A click inside must not — hence the stop on the panel, which is the bug every hand-rolled modal
 * ships once: selecting text in a field, releasing outside the panel, and watching the dialog
 * vanish with the half-finished form.
 */
export default function Modal({ open, title, onClose, children, footer, width = 420 }) {
  useEffect(() => {
    if (!open) return undefined;
    const escape = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      onMouseDown={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 80,
        background: 'rgba(0,0,0,0.45)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
      }}
    >
      <div
        onMouseDown={e => e.stopPropagation()}
        style={{
          width: '100%', maxWidth: width, maxHeight: '90vh', overflowY: 'auto',
          background: 'var(--surface)', border: '1px solid var(--border-strong)',
          borderRadius: 16, boxShadow: '0 20px 50px var(--shadow)', padding: '22px 24px',
          boxSizing: 'border-box',
        }}
      >
        {title && (
          <h2 style={{ fontSize: 17, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 16px' }}>
            {title}
          </h2>
        )}
        {children}
        {footer && (
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 9, marginTop: 20 }}>
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
