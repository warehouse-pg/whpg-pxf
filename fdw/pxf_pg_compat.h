/*
 * pxf_pg_compat.h
 *		  Compatibility shim that lets the PXF foreign-data wrapper build
 *		  against both WarehousePG 7 (PostgreSQL 12 based) and WarehousePG 19
 *		  (PostgreSQL 19 based) from a single source tree.
 *
 * The COPY internals PXF drives changed shape across the two server
 * generations.  The differences are absorbed here so the module body can be
 * written once, in the modern shape, and so a future platform tier is a
 * change to this file rather than a change scattered across call sites.
 *
 * IDENTIFICATION
 *		  fdw/pxf_pg_compat.h
 */

#ifndef PXF_PG_COMPAT_H
#define PXF_PG_COMPAT_H

#include "commands/copy.h"

/*
 * This is a WarehousePG platform-tier boundary, not an upstream feature test.
 * WarehousePG 7 is PostgreSQL 12 based and WarehousePG 19 is PostgreSQL 19
 * based, so the PostgreSQL major separates the two cleanly -- but the number
 * is only a convenient discriminator.  Testing the individual upstream
 * releases named below would be wrong here, because WarehousePG 7 diverges
 * from stock PostgreSQL 12 in exactly these areas (its BeginCopyFrom() has no
 * whereClause parameter, for one).  A third platform means a new tier here,
 * not new guards at the call sites.
 */
#if PG_VERSION_NUM >= 130000

/*
 * PostgreSQL 14 split CopyState into CopyFromState and CopyToState (upstream
 * commit c532d15dddf, "Split copy.c into four files") and moved the
 * CopyFromStateData definition into commands/copyfrom_internal.h.  Both type
 * names exist natively here; WarehousePG 19 also keeps a legacy CopyState
 * alias, but the module names the direction it means rather than rely on it.
 *
 * copyto_internal.h is a WarehousePG addition, not an upstream header:
 * upstream keeps CopyToState opaque, so cstate->copycontext below would not
 * compile against stock PostgreSQL.  That is fine for us -- this module only
 * ever builds against WarehousePG -- but it is the reason the tier boundary
 * above cannot be replaced by an upstream-version test.
 */
#include "commands/copyfrom_internal.h"
#include "commands/copyto_internal.h"

/*
 * PostgreSQL 14 moved the COPY format options (null_print, binary, ...) into
 * a nested CopyFormatOptions struct.
 */
#define PXF_COPY_OPTS(cstate)	((cstate)->opts)

/*
 * PostgreSQL 19 replaced the individual format booleans with a CopyFormat
 * enum: COPY_FORMAT_BINARY is on upstream master only, and REL_16 through
 * REL_18 still carry `bool binary`.
 */
#define PXF_COPY_IS_BINARY(cstate) \
	(PXF_COPY_OPTS(cstate).format == COPY_FORMAT_BINARY)

/*
 * PostgreSQL 15 removed the Value union in favour of per-type nodes;
 * makeString() now returns String *.
 */
typedef String PxfStringValue;

#else							/* WarehousePG 6 / 7 */

/*
 * A single CopyState serves both directions.  Alias the modern names onto it
 * so the module body can say which direction a state belongs to.
 */
typedef CopyState CopyFromState;
typedef CopyState CopyToState;

/*
 * Format options are flat fields on CopyStateData, so "the options" is just
 * the state itself.
 */
#define PXF_COPY_OPTS(cstate)	(*(cstate))

#define PXF_COPY_IS_BINARY(cstate)	(PXF_COPY_OPTS(cstate).binary)

typedef Value PxfStringValue;

#endif

#endif							/* PXF_PG_COMPAT_H */
