package com.stolsvik.mats.api.intercept;

/**
 * Marker interface to denote a metrics interceptor. The MatsFactory will only allow one such singleton interceptor,
 * and remove any previously installed when installing a new. The MatsFactory will install the most
 * verbose standard variant upon creation.
 *
 * @author Endre Stølsvik - 2021-02-19 13:21 - http://endre.stolsvik.com
 */
public interface MatsMetricsInterceptor {
}
