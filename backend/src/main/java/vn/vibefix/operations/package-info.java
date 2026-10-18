/**
 * Layer 1 (AD-3): External Operations (AD-6). Every effect outside the platform (GitHub, VNPay,
 * SMTP) and every long internal job is requested through {@link vn.vibefix.operations.ExternalOperations}
 * in the caller's transaction, under the key {@code type:causingRecordId}, and run later by a poller
 * outside any transaction. Each type has one {@link vn.vibefix.operations.OperationHandler}, which an
 * upper module implements (AD-3 port); completion writes the status, an audit entry, and an
 * {@link vn.vibefix.operations.OperationCompleted} event in one transaction.
 * <p>
 * Inside L1 the order is {@code audit}, then {@code operations}, then {@code notification}: this
 * module may call {@code audit} (ModuleLayersTest).
 * <p>
 * Public API: {@link vn.vibefix.operations.ExternalOperations}, {@link vn.vibefix.operations.OperationRequest},
 * {@link vn.vibefix.operations.OperationTicket}, {@link vn.vibefix.operations.OperationStatus},
 * {@link vn.vibefix.operations.OperationHandler}, {@link vn.vibefix.operations.OperationRun},
 * {@link vn.vibefix.operations.Outcome}, {@link vn.vibefix.operations.OperationPolicy},
 * {@link vn.vibefix.operations.Lane}, {@link vn.vibefix.operations.OperationCompleted} and
 * {@link vn.vibefix.operations.FailedOperations}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit" })
package vn.vibefix.operations;

import org.springframework.modulith.ApplicationModule;
