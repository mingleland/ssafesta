import { useEffect } from 'react';
import { useSession } from '../../auth/model/session';
import { startCoinGrantNotifications } from '../model/coinGrantNotification';

export function CoinGrantNotificationListener() {
  const { kind } = useSession();

  useEffect(() => {
    if (kind !== 'member' || import.meta.env.MODE === 'test') return;
    return startCoinGrantNotifications();
  }, [kind]);

  return null;
}
