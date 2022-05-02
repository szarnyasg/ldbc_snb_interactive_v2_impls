USE ldbc;
-- create index on foreign keys
CREATE INDEX forum_moderatorid ON forum (f_moderatorid);
CREATE INDEX forum_person_forumid ON forum_person (fp_forumid);
CREATE INDEX forum_person_personid ON forum_person (fp_personid);
CREATE INDEX forum_tag_forumid ON forum_tag (ft_forumid);
CREATE INDEX forum_tag_tagid ON forum_tag (ft_tagid);
CREATE INDEX knows_person1id ON knows (k_person1id);
CREATE INDEX knows_person2id ON knows (k_person2id);
CREATE INDEX likes_personid ON likes (l_personid);
CREATE INDEX likes_messageid ON likes (l_messageid);
CREATE INDEX organisation_placeid ON organisation (o_placeid);
CREATE INDEX person_placeid ON person (p_placeid);
CREATE INDEX person_company_personid ON person_company (pc_personid);
CREATE INDEX person_company_organisationid ON person_company (pc_organisationid);
CREATE INDEX person_email_personid ON person_email (pe_personid);
CREATE INDEX person_language_personid ON person_language (plang_personid);
CREATE INDEX person_tag_personid ON person_tag (pt_personid);
CREATE INDEX person_tag_tagid ON person_tag (pt_tagid);
CREATE INDEX person_university_personid ON person_university (pu_personid);
CREATE INDEX person_university_organisationid ON person_university (pu_organisationid);
CREATE INDEX place_containerplaceid ON place (pl_containerplaceid);
CREATE INDEX message_creatorid ON message (m_creatorid);
CREATE INDEX message_locationid ON message (m_locationid);
CREATE INDEX message_forumid ON message (m_ps_forumid);
CREATE INDEX message_replyof ON message (m_c_replyof);
CREATE INDEX message_tag_messageid ON message_tag (mt_messageid);
CREATE INDEX message_tag_tagid ON message_tag (mt_tagid);
CREATE INDEX tag_tagclassid ON tag (t_tagclassid);
CREATE INDEX tagclass_subclassoftagclassid ON tagclass (tc_subclassoftagclassid);
