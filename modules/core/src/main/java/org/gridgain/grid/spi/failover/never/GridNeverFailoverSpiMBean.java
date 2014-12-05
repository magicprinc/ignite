/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.failover.never;

import org.apache.ignite.mbean.*;
import org.gridgain.grid.spi.*;

/**
 * Management bean for {@link GridNeverFailoverSpi}.
 */
@IgniteMBeanDescription("MBean that provides access to never failover SPI configuration.")
public interface GridNeverFailoverSpiMBean extends IgniteSpiManagementMBean {
    // No-op.
}
