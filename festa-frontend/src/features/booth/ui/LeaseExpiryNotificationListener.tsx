// App-wide D07 listener — keeps a member's lease reminder subscribed across every React route.
import { useEffect } from 'react';
import { useSession } from '../../auth/model/session';
import { startLeaseExpiryNotifications } from '../model/leaseExpiryNotification';

export function LeaseExpiryNotificationListener() {
  const { kind } = useSession();

  useEffect(() => {
    if (kind !== 'member' || import.meta.env.MODE === 'test') return;
    return startLeaseExpiryNotifications();
  }, [kind]);

  return null;
}
