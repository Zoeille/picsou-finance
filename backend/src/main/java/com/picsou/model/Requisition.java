package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "requisition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requisition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GoCardless requisition UUID */
    @Column(name = "requisition_id", nullable = false, unique = true, length = 100)
    private String requisitionId;

    @Column(name = "institution_id", nullable = false, length = 100)
    private String institutionId;

    @Column(name = "institution_name", length = 200)
    private String institutionName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "requisition_status")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Builder.Default
    private RequisitionStatus status = RequisitionStatus.CREATED;

    @Column(name = "auth_link", columnDefinition = "TEXT")
    private String authLink;
}
