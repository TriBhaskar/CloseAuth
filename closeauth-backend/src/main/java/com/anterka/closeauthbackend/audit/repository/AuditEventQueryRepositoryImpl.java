package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria-API implementation of {@link AuditEventQueryRepository}. Builds the same {@code WHERE} predicate list for
 * both the page query and the count query, so the total always matches the filtered page. Tenant-scoping is applied
 * here (not just at the controller gate) — defense in depth per §7.11.
 *
 * <p>Spring Data detects this by the {@code Impl} postfix convention and instantiates it (autowiring the
 * {@code EntityManager}) as the custom fragment of {@link AuditEventRepository} — no stereotype annotation needed.
 */
public class AuditEventQueryRepositoryImpl implements AuditEventQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<AuditEvent> search(AuditEventQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEvent> cq = cb.createQuery(AuditEvent.class);
        Root<AuditEvent> root = cq.from(AuditEvent.class);
        cq.select(root)
                .where(predicates(cb, root, query).toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("createdAt")));
        return entityManager.createQuery(cq)
                .setFirstResult(Math.max(query.offset(), 0))
                .setMaxResults(Math.max(query.limit(), 1))
                .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(AuditEventQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditEvent> root = cq.from(AuditEvent.class);
        cq.select(cb.count(root)).where(predicates(cb, root, query).toArray(Predicate[]::new));
        return entityManager.createQuery(cq).getSingleResult();
    }

    private List<Predicate> predicates(CriteriaBuilder cb, Root<AuditEvent> root, AuditEventQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        // Tenant-scoping — applied whenever a tenant is in scope (always, for the customer endpoint). NO exceptions.
        if (query.tenantId() != null) {
            predicates.add(cb.equal(root.get("tenantId"), query.tenantId()));
        }
        if (query.eventType() != null) {
            predicates.add(cb.equal(root.get("eventType"), query.eventType()));
        }
        if (query.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
        }
        if (query.to() != null) {
            predicates.add(cb.lessThan(root.get("createdAt"), query.to()));
        }
        if (query.subjectUserId() != null) {
            predicates.add(cb.equal(root.get("subjectUserId"), query.subjectUserId()));
        }
        if (query.actorClientRegisteredId() != null) {
            predicates.add(cb.equal(root.get("actorClientRegisteredId"), query.actorClientRegisteredId()));
        }
        if (query.actorUserId() != null) {
            predicates.add(cb.equal(root.get("actorUserId"), query.actorUserId()));
        }
        return predicates;
    }
}
