import { Navigate, Route, Routes } from 'react-router';
import { useAuth } from './auth/context';
import { Activity } from './pages/Activity';
import { AddMoney } from './pages/AddMoney';
import { Cover } from './pages/Cover';
import { Health } from './pages/Health';
import { Home } from './pages/Home';
import { Ledger } from './pages/Ledger';
import { Profile } from './pages/Profile';
import { Send } from './pages/Send';
import { TransferStatus } from './pages/TransferStatus';

export function App() {
  const { status } = useAuth();

  if (status === 'checking') {
    // The cover, without its form, while the saved session is checked. Avoids flashing the
    // sign-in form at someone who is about to be let straight in.
    return <Cover checking />;
  }

  if (status === 'signedOut') {
    // Shown at whatever address was asked for, so someone arriving from a payment QR code lands
    // on that payment once they have signed in.
    return <Cover />;
  }

  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/activity" element={<Activity />} />
      <Route path="/ledger" element={<Ledger />} />
      <Route path="/send" element={<Send />} />
      <Route path="/add" element={<AddMoney />} />
      <Route path="/transfers/:transferId" element={<TransferStatus />} />
      <Route path="/profile" element={<Profile />} />
      <Route path="/admin" element={<Health />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
