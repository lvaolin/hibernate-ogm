/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.failure.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.ogm.dialect.batch.spi.InsertOrUpdateAssociationOperation;
import org.hibernate.ogm.dialect.batch.spi.InsertOrUpdateTupleOperation;
import org.hibernate.ogm.dialect.batch.spi.Operation;
import org.hibernate.ogm.dialect.batch.spi.OperationsQueue;
import org.hibernate.ogm.dialect.batch.spi.RemoveAssociationOperation;
import org.hibernate.ogm.dialect.batch.spi.RemoveTupleOperation;
import org.hibernate.ogm.dialect.eventstate.impl.EventContextManager;
import org.hibernate.ogm.dialect.impl.ForwardingGridDialect;
import org.hibernate.ogm.dialect.spi.AssociationContext;
import org.hibernate.ogm.dialect.spi.GridDialect;
import org.hibernate.ogm.dialect.spi.TupleContext;
import org.hibernate.ogm.failure.operation.GridDialectOperation;
import org.hibernate.ogm.failure.operation.impl.CreateAssociationWithKeyImpl;
import org.hibernate.ogm.failure.operation.impl.CreateTupleImpl;
import org.hibernate.ogm.failure.operation.impl.CreateTupleWithKeyImpl;
import org.hibernate.ogm.failure.operation.impl.ExecuteBatchImpl;
import org.hibernate.ogm.failure.operation.impl.InsertOrUpdateAssociationImpl;
import org.hibernate.ogm.failure.operation.impl.InsertOrUpdateTupleImpl;
import org.hibernate.ogm.failure.operation.impl.InsertTupleImpl;
import org.hibernate.ogm.failure.operation.impl.RemoveAssociationImpl;
import org.hibernate.ogm.failure.operation.impl.RemoveTupleImpl;
import org.hibernate.ogm.failure.operation.impl.RemoveTupleWithOptimisticLockImpl;
import org.hibernate.ogm.failure.operation.impl.UpdateTupleWithOptimisticLockImpl;
import org.hibernate.ogm.model.key.spi.AssociationKey;
import org.hibernate.ogm.model.key.spi.EntityKey;
import org.hibernate.ogm.model.key.spi.EntityKeyMetadata;
import org.hibernate.ogm.model.spi.Association;
import org.hibernate.ogm.model.spi.Tuple;

/**
 * A grid dialect which tracks all applied and failing operations and passes them on to the {@link OperationCollector}
 * which in turn makes them available to the registered {@link ErrorHandler}.
 *
 * @author Gunnar Morling
 */
public class InvocationCollectingGridDialect extends ForwardingGridDialect<Serializable> {

	private final EventContextManager eventContext;

	public InvocationCollectingGridDialect(GridDialect gridDialect, EventContextManager eventContextManager) {
		super( gridDialect );
		this.eventContext = eventContextManager;
	}

	@Override
	public void insertOrUpdateTuple(EntityKey key, Tuple tuple, TupleContext tupleContext) {
		super.insertOrUpdateTuple( key, tuple, tupleContext );
		handleAppliedOperation( new InsertOrUpdateTupleImpl( key, tuple ) );
	}

	@Override
	public void executeBatch(OperationsQueue queue) {
		OperationsQueue newQueue = new OperationsQueue();
		List<GridDialectOperation> operations = new ArrayList<>();

		if ( !queue.isClosed() ) {
			Operation operation = queue.poll();

			// TODO OGM-766 Avoid the looping + re-creation
			while ( operation != null ) {
				newQueue.add( operation );

				if ( operation instanceof InsertOrUpdateTupleOperation ) {
					InsertOrUpdateTupleOperation insertOrUpdateTuple = (InsertOrUpdateTupleOperation) operation;
					operations.add( new InsertOrUpdateTupleImpl( insertOrUpdateTuple.getEntityKey(), insertOrUpdateTuple.getTuple() ) );
				}
				else if ( operation instanceof RemoveTupleOperation ) {
					RemoveTupleOperation removeTuple = (RemoveTupleOperation) operation;
					operations.add( new RemoveTupleImpl( removeTuple.getEntityKey() ) );
				}
				else if ( operation instanceof InsertOrUpdateAssociationOperation ) {
					InsertOrUpdateAssociationOperation insertOrUpdateAssociationOperation = (InsertOrUpdateAssociationOperation) operation;
					operations.add( new InsertOrUpdateAssociationImpl(
							insertOrUpdateAssociationOperation.getAssociationKey(),
							insertOrUpdateAssociationOperation.getAssociation() )
					);
				}
				else if ( operation instanceof RemoveAssociationOperation ) {
					RemoveAssociationOperation removeAssociationOperation = (RemoveAssociationOperation) operation;
					operations.add( new RemoveAssociationImpl( removeAssociationOperation.getAssociationKey() ) );
				}

				operation = queue.poll();
			}
		}

		super.executeBatch( newQueue );
		handleAppliedOperation( new ExecuteBatchImpl( operations ) );
	}

	@Override
	public Tuple createTuple(EntityKey key, TupleContext tupleContext) {
		Tuple tuple = super.createTuple( key, tupleContext );
		handleAppliedOperation( new CreateTupleWithKeyImpl( key ) );
		return tuple;
	}

	@Override
	public void removeTuple(EntityKey key, TupleContext tupleContext) {
		super.removeTuple( key, tupleContext );
		handleAppliedOperation( new RemoveTupleImpl( key ) );
	}

	@Override
	public Association createAssociation(AssociationKey key, AssociationContext associationContext) {
		Association association = super.createAssociation( key, associationContext );
		handleAppliedOperation( new CreateAssociationWithKeyImpl( key ) );
		return association;
	}

	@Override
	public void insertOrUpdateAssociation(AssociationKey key, Association association, AssociationContext associationContext) {
		super.insertOrUpdateAssociation( key, association, associationContext );
		handleAppliedOperation( new InsertOrUpdateAssociationImpl( key, association ) );
	}

	@Override
	public void removeAssociation(AssociationKey key, AssociationContext associationContext) {
		super.removeAssociation( key, associationContext );
		handleAppliedOperation( new RemoveAssociationImpl( key ) );
	}

	// IdentityColumnAwareGridDialect

	@Override
	public Tuple createTuple(EntityKeyMetadata entityKeyMetadata, TupleContext tupleContext) {
		Tuple tuple = super.createTuple( entityKeyMetadata, tupleContext );
		handleAppliedOperation( new CreateTupleImpl( entityKeyMetadata ) );
		return tuple;
	}

	@Override
	public void insertTuple(EntityKeyMetadata entityKeyMetadata, Tuple tuple, TupleContext tupleContext) {
		super.insertTuple( entityKeyMetadata, tuple, tupleContext );
		handleAppliedOperation( new InsertTupleImpl( entityKeyMetadata, tuple ) );
	}

	// OptimisticLockingAwareGridDialect

	@Override
	public boolean updateTupleWithOptimisticLock(EntityKey entityKey, Tuple oldLockState, Tuple tuple, TupleContext tupleContext) {
		boolean success = super.updateTupleWithOptimisticLock( entityKey, oldLockState, tuple, tupleContext );

		if ( success ) {
			handleAppliedOperation( new UpdateTupleWithOptimisticLockImpl( entityKey, oldLockState, tuple ) );
		}

		return success;
	}

	@Override
	public boolean removeTupleWithOptimisticLock(EntityKey entityKey, Tuple oldLockState, TupleContext tupleContext) {
		boolean success = super.removeTupleWithOptimisticLock( entityKey, oldLockState, tupleContext );

		if ( success ) {
			handleAppliedOperation( new RemoveTupleWithOptimisticLockImpl( entityKey, oldLockState ) );
		}

		return success;
	}

	private void handleAppliedOperation(GridDialectOperation operation) {
		getOperationCollector().addAppliedOperation( operation );
	}

	/**
	 * Returns the {@link OperationCollector}. Must not store this as a field as it is flush-cycle scoped!
	 */
	private OperationCollector getOperationCollector() {
		return eventContext.get( OperationCollector.class );
	}
}